package org.jocean.http.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Actions;
import rx.subscriptions.Subscriptions;

public class InboundEndpointSupport implements InboundEndpoint {

    private static final Logger LOG =
            LoggerFactory.getLogger(InboundEndpointSupport.class);
    
    public InboundEndpointSupport(
            final InterfaceSelector selector,
            final Channel channel,
            final Observable<? extends HttpObject> message,
            final HttpMessageHolder holder,
            final TrafficCounter trafficCounter,
            final Action1<Action0> onTerminate) {
        // disable auto read
        channel.config().setAutoRead(false);
        channel.read();
        
        this._channel = channel;
        this._message = message;
        this._holder = holder;
        this._trafficCounter = trafficCounter;
        
        RxNettys.applyToChannelWithUninstall(
            channel, 
            onTerminate, 
            APPLY.ON_CHANNEL_READCOMPLETE,
            new Action0() {
                @Override
                public void call() {
                    unreadBeginUpdater.set(InboundEndpointSupport.this, System.currentTimeMillis());
                    @SuppressWarnings("unchecked")
                    final Action1<InboundEndpoint> readPolicy = 
                            readPolicyUpdater.get(InboundEndpointSupport.this);
                    if (null != readPolicy) {
                        readPolicy.call(InboundEndpointSupport.this);
                    }
                    // _onReadCompletes.foreachComponent(_CALL_READCOMPLETE, InboundEndpointSupport.this);
                }});
        this._op = selector.build(Op.class, OP_WHEN_ACTIVE, OP_WHEN_UNACTIVE);
    }
    
    private final Op _op;
    
    protected interface Op {
        public Action0 addReadComplete(final InboundEndpointSupport support, 
                final Action1<InboundEndpoint> onReadComplete);
        public void setAutoRead(final InboundEndpointSupport support,
                final boolean autoRead);
        public void readMessage(final InboundEndpointSupport support); 
        public Observable<? extends HttpObject> message(final InboundEndpointSupport support);
    }
    
    private static final Op OP_WHEN_ACTIVE = new Op() {
        @Override
        public Action0 addReadComplete(final InboundEndpointSupport support,
                final Action1<InboundEndpoint> onReadComplete) {
            support._onReadCompletes.addComponent(onReadComplete);
            return new Action0() {
                @Override
                public void call() {
                    support._onReadCompletes.removeComponent(onReadComplete);
                }};
        }

        @Override
        public void setAutoRead(final InboundEndpointSupport support,
                final boolean autoRead) {
            support._channel.config().setAutoRead(autoRead);
        }

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
        public Action0 addReadComplete(InboundEndpointSupport support,
                Action1<InboundEndpoint> onReadComplete) {
            return Actions.empty();
        }

        @Override
        public void setAutoRead(InboundEndpointSupport support,
                boolean autoRead) {
        }

        @Override
        public void readMessage(InboundEndpointSupport support) {
        }

        @Override
        public Observable<? extends HttpObject> message(
                InboundEndpointSupport support) {
            return Observable.error(new RuntimeException("inbound unactived"));
        }
    };
    
    private static final Action1_N<Action1<InboundEndpoint>> _CALL_READCOMPLETE = 
    new Action1_N<Action1<InboundEndpoint>>() {
        @Override
        public void call(final Action1<InboundEndpoint> onReadComplete, final Object...args) {
            try {
                onReadComplete.call((InboundEndpoint)args[0]);
            } catch (Exception e) {
                LOG.warn("exception when inbound({}) invoke onReadComplete({}), detail: {}",
                    args[0], 
                    onReadComplete, 
                    ExceptionUtils.exception2detail(e));
            }
        }};
            
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
    
//    @Override
//    public void setAutoRead(final boolean autoRead) {
//        _op.setAutoRead(this, autoRead);
//    }

    @Override
    public void readMessage() {
        _op.readMessage(this);
    }

    @Override
    public void setReadPolicy(final Action1<InboundEndpoint> readPolicy) {
        readPolicyUpdater.set(this, readPolicy);
    }
    
//    @Override
//    public Action0 doOnReadComplete(
//            final Action1<InboundEndpoint> onReadComplete) {
//        return _op.addReadComplete(this, onReadComplete);
//    }

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
    
    private final COWCompositeSupport<Action1<InboundEndpoint>> _onReadCompletes = 
            new COWCompositeSupport<>();
    
    @SuppressWarnings("deprecation")
    private final Observable<HttpObject> _inboundProxy = Observable.create(new OnSubscribe<HttpObject>() {
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
                _message.subscribe(serializedSubscriber);
            }
        }});

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<InboundEndpointSupport, Action1> readPolicyUpdater =
            AtomicReferenceFieldUpdater.newUpdater(InboundEndpointSupport.class, Action1.class, "_readPolicy");
    
    @SuppressWarnings("unused")
    private volatile Action1<InboundEndpoint> _readPolicy = CONST.ALWAYS;
    
    private static final AtomicLongFieldUpdater<InboundEndpointSupport> unreadBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(InboundEndpointSupport.class, "_unreadBegin");
    
    @SuppressWarnings("unused")
    private volatile long _unreadBegin = 0;
    
    private final Observable<? extends HttpObject> _message;
    private final List<Subscriber<? super HttpObject>> _subscribers = 
            new CopyOnWriteArrayList<>();
    private final Channel _channel;
    private final HttpMessageHolder _holder;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
}
