package org.jocean.http.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subscriptions.Subscriptions;

public class InboundEndpointSupport implements InboundEndpoint {

    private static final Logger LOG =
            LoggerFactory.getLogger(InboundEndpointSupport.class);
    
    public InboundEndpointSupport(
            final InterfaceSelector selector,
            final Channel channel,
            final Transformer<HttpObject, HttpObject> transform,
            final TrafficCounter trafficCounter,
            final Action1<Action0> onTerminate) {
        
        this._holder = new HttpMessageHolder();
        onTerminate.call(_holder.release());
        
        final Observable<? extends HttpObject> message = 
                RxNettys.inboundFromChannel(channel, onTerminate)
                .compose(_holder.<HttpObject>assembleAndHold())
                .compose(transform)
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        setMessageCompleted();
                    }})
                .cache()
                .compose(RxNettys.duplicateHttpContent())
                ;
        
        message.subscribe(
            RxSubscribers.ignoreNext(),
            new Action1<Throwable>() {
                @Override
                public void call(final Throwable e) {
                    LOG.warn("inbound({})'s internal request subscriber invoke with onError {}", 
                            this, ExceptionUtils.exception2detail(e));
                }});
        
        // disable auto read
        channel.read();
        
        this._channel = channel;
        this._trafficCounter = trafficCounter;
        this._inboundProxy = buildProxy(message);
        
        RxNettys.applyToChannelWithUninstall(
            channel, 
            onTerminate, 
            APPLY.ON_CHANNEL_READCOMPLETE,
            new Action0() {
                @Override
                public void call() {
                    unreadBeginUpdater.set(InboundEndpointSupport.this, System.currentTimeMillis());
                    if (!isMessageCompleted()) {
                        //  if inbound message not completed, continue to read inbound
                        @SuppressWarnings("unchecked")
                        final Action1<InboundEndpoint> readPolicy = 
                                readPolicyUpdater.get(InboundEndpointSupport.this);
                        if (null != readPolicy) {
                            try {
                                readPolicy.call(InboundEndpointSupport.this);
                            } catch (Exception e) {
                                LOG.warn("exception when invoke read policy({}), detail: {}",
                                    readPolicy, ExceptionUtils.exception2detail(e));
                            }
                        }
                    }
                }});
        this._op = selector.build(Op.class, OP_WHEN_ACTIVE, OP_WHEN_UNACTIVE);
    }

    private void setMessageCompleted() {
        completedUpdater.set(this, 1);
    }

    private boolean isMessageCompleted() {
        return 1 == completedUpdater.get(this);
    }

    @SuppressWarnings("deprecation")
    private Observable<HttpObject> buildProxy(
            final Observable<? extends HttpObject> message) {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                final Subscriber<? super HttpObject> serializedSubscriber = RxSubscribers.serialized(subscriber);
                if (!serializedSubscriber.isUnsubscribed()) {
                    _subscribers.add(serializedSubscriber);
                    serializedSubscriber.add(Subscriptions.create(new Action0() {
                        @Override
                        public void call() {
                            _subscribers.remove(serializedSubscriber);
                        }}));
                    message.subscribe(serializedSubscriber);
                }
            }});
    }
    
    private final Op _op;
    
    protected interface Op {
        public void readMessage(final InboundEndpointSupport support); 
        public Observable<? extends HttpObject> message(final InboundEndpointSupport support);
    }
    
    private static final Op OP_WHEN_ACTIVE = new Op() {
        @Override
        public void readMessage(final InboundEndpointSupport support) {
            support.doChannelRead();
        }

        @Override
        public Observable<? extends HttpObject> message(
                final InboundEndpointSupport support) {
            return support._inboundProxy;
        }
    };
    
    private static final Op OP_WHEN_UNACTIVE = new Op() {
        @Override
        public void readMessage(InboundEndpointSupport support) {
        }

        @Override
        public Observable<? extends HttpObject> message(
                InboundEndpointSupport support) {
            return Observable.error(new RuntimeException("inbound unactived"));
        }
    };
    
    public void fireAllSubscriberUnactive() {
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject>[] subscribers = 
            (Subscriber<? super HttpObject>[])this._subscribers.toArray(new Subscriber[0]);
        for (Subscriber<? super HttpObject> subscriber : subscribers) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onError(new RuntimeException("inbound unactived"));
                } catch (Exception e) {
                    LOG.warn("exception when invoke ({}).onError, detail: {}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }
    
    public int subscribersCount() {
        return this._subscribers.size();
    }
    
    private void doChannelRead() {
        this._channel.read();
        unreadBeginUpdater.set(this, 0);
    }

    @Override
    public long unreadDurationInMs() {
        final long begin = unreadBeginUpdater.get(this);
        return 0 == begin ? 0 : System.currentTimeMillis() - begin;
    }

    @Override
    public void readMessage() {
        _op.readMessage(this);
    }

    @Override
    public void setReadPolicy(final Action1<InboundEndpoint> readPolicy) {
        readPolicyUpdater.set(this, readPolicy);
    }
    
    @Override
    public long timeToLive() {
        return System.currentTimeMillis() - _createTimeMillis;
    }

    @Override
    public long inboundBytes() {
        return _trafficCounter.inboundBytes();
    }

    @Override
    public Observable<? extends HttpObject> message() {
        return _op.message(this);
    }

    @Override
    public HttpMessageHolder messageHolder() {
        return _holder;
    }

    @Override
    public int holdingMemorySize() {
        return _holder.retainedByteBufSize();
    }
    
    private final Observable<HttpObject> _inboundProxy;
    
    private static final AtomicIntegerFieldUpdater<InboundEndpointSupport> completedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(InboundEndpointSupport.class, "_isCompleted");
    
    @SuppressWarnings("unused")
    private volatile int _isCompleted = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<InboundEndpointSupport, Action1> readPolicyUpdater =
            AtomicReferenceFieldUpdater.newUpdater(InboundEndpointSupport.class, Action1.class, "_readPolicy");
    
    @SuppressWarnings("unused")
    private volatile Action1<InboundEndpoint> _readPolicy = CONST.ALWAYS;
    
    private static final AtomicLongFieldUpdater<InboundEndpointSupport> unreadBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(InboundEndpointSupport.class, "_unreadBegin");
    
    @SuppressWarnings("unused")
    private volatile long _unreadBegin = 0;
    
    private final List<Subscriber<? super HttpObject>> _subscribers = 
            new CopyOnWriteArrayList<>();
    private final Channel _channel;
    private final HttpMessageHolder _holder;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
}
