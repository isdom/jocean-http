package org.jocean.http.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
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
import rx.functions.Func0;
import rx.subscriptions.Subscriptions;

public class InboundEndpointSupport<T> implements InboundEndpoint {

    private static final Logger LOG =
            LoggerFactory.getLogger(InboundEndpointSupport.class);
    
    public InboundEndpointSupport(
            final FuncSelector<T> selector,
            final Channel channel,
            final Observable<? extends HttpObject> message,
            final HttpMessageHolder holder,
            final TrafficCounter trafficCounter,
            final Action1<Action0> onTerminate) {
        this._selector = selector;
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
                    _onReadCompletes.foreachComponent(_callReadComplete);
                }});
        
        this._doAddReadComplete = 
            RxActions.toAction1(
                this._selector.submitWhenActive(
                    new Action1_N<T>() {
                        @SuppressWarnings("unchecked")
                        @Override
                        public void call(final T t,
                                final Object... args) {
                            _onReadCompletes.addComponent((Action1<InboundEndpoint>)args[0]);
                        }})
            );
        
        this._doSetInboundAutoRead = 
            RxActions.toAction1(
                this._selector.submitWhenActive(
                    new Action1_N<T>() {
                        @Override
                        public void call(final T t,
                                final Object... args) {
                            _channel.config().setAutoRead((Boolean)args[0]);
                        }})
            );
        
        this._doReadInbound = 
            RxActions.toAction0(
                this._selector.submitWhenActive(
                    new Action1_N<T>() {
                        @Override
                        public void call(final T t,
                                final Object... args) {
                            _channel.read();
                        }})
            );
        
        this._doGetMessage = 
            RxFunctions.toFunc0(
                this._selector
                .callWhenActive(
                    new Func1_N<T,Observable<? extends HttpObject>>() {
                        @Override
                        public Observable<? extends HttpObject> call(final T t,
                                final Object... args) {
                            return _inboundProxy;
                        }})
                .callWhenDestroyed(
                    new Func1_N<T,Observable<? extends HttpObject>>() {
                        @Override
                        public Observable<? extends HttpObject> call(final T t,
                                final Object... args) {
                            return Observable.error(new RuntimeException("inbound unactived"));
                        }})
            );
    }
    
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
    
    @Override
    public void setAutoRead(final boolean autoRead) {
        _doSetInboundAutoRead.call(autoRead);
    }

    @Override
    public void readMessage() {
        _doReadInbound.call();
    }

    @Override
    public Action0 doOnReadComplete(
            final Action1<InboundEndpoint> onReadComplete) {
        _doAddReadComplete.call(onReadComplete);
        return new Action0() {
            @Override
            public void call() {
                _selector.submitWhenActive(REMOVE_READCOMPLETE).call(onReadComplete);
            }};
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
        return _doGetMessage.call();
    }

    @Override
    public HttpMessageHolder messageHolder() {
        return _holder;
    }

    @Override
    public int holdingMemorySize() {
        return _holder.retainedByteBufSize();
    }
    
    private final Action1<Action1<InboundEndpoint>> _callReadComplete = new Action1<Action1<InboundEndpoint>>() {
        @Override
        public void call(final Action1<InboundEndpoint> onReadComplete) {
            try {
                onReadComplete.call(InboundEndpointSupport.this);
            } catch (Exception e) {
                LOG.warn("exception when inbound({}) invoke onReadComplete({}), detail: {}",
                    InboundEndpointSupport.this, 
                    onReadComplete, 
                    ExceptionUtils.exception2detail(e));
            }
        }};
        
    private final Action1_N<T> REMOVE_READCOMPLETE = 
        new Action1_N<T>() {
            @SuppressWarnings("unchecked")
            @Override
            public void call(final T t,
                    final Object... args) {
                _onReadCompletes.removeComponent((Action1<InboundEndpoint>)args[0]);
            }};
            
    private final COWCompositeSupport<Action1<InboundEndpoint>> _onReadCompletes = 
            new COWCompositeSupport<>();
    
    private final Action1<Action1<InboundEndpoint>> _doAddReadComplete;
    private final Action1<Boolean> _doSetInboundAutoRead;
    private final Action0 _doReadInbound;
    private final Func0<Observable<? extends HttpObject>> _doGetMessage;
    
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

    private final FuncSelector<T> _selector;
    private final Observable<? extends HttpObject> _message;
    private final List<Subscriber<? super HttpObject>> _subscribers = 
            new CopyOnWriteArrayList<>();
    private final Channel _channel;
    private final HttpMessageHolder _holder;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
}
