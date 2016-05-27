/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServer.CachedHttpTrade;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.idiom.ActiveHolder;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.RxActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade implements HttpTrade {
    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [request's subscribers.size=")
                .append(_requestSubscribers.size())
                .append(", isRequestReceived=").append(_isRequestReceived.get())
                .append(", isRequestCompleted=").append(_isRequestCompleted.get())
                .append(", isResponseSended=").append(_isResponseSended.get())
                .append(", isResponseCompleted=").append(_isResponseCompleted.get())
                .append(", isKeepAlive=").append(_isKeepAlive.get())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    DefaultHttpTrade(
        final Channel channel, 
        final Observable<? extends HttpObject> requestObservable) {
        this._channel = channel;
        //  TODO when to unsubscribe
        requestObservable.subscribe(this._requestRelay);
    }

    @Override
    public boolean isActive() {
        return this._activeHolder.isActive();
    }

    @Override
    public boolean isEndedWithKeepAlive() {
        return (this._isRequestCompleted.get() 
            && this._isResponseCompleted.get()
            && this._isKeepAlive.get());
    }

    @Override
    public void abort() {
        this._activeHolder.destroy(this._closeChannelAndInvokeDoOnClose);
    }
    
    @Override
    public Object transport() {
        return this._channel;
    }
    
    @Override
    public Observable<? extends HttpObject> inboundRequest() {
        return this._requestObservable;
    }

    @Override
    public Subscription outboundResponse(final Observable<? extends HttpObject> response) {
        return outboundResponse(response, null);
    }
    
    @Override
    public Subscription outboundResponse(
            final Observable<? extends HttpObject> response,
            final Action1<Throwable> onError) {
        synchronized(this._subscriptionOfResponse) {
            //  对 outboundResponse 方法加锁
            final Subscription oldsubsc =  this._subscriptionOfResponse.get();
            if (null==oldsubsc ||
                (oldsubsc.isUnsubscribed() && !this._isResponseSended.get())) {
                final Subscription newsubsc = response.subscribe(
                        this._responseOnNext,
                        null!=onError ? onError : this._responseOnError,
                        this._responseOnCompleted);
                this._subscriptionOfResponse.set(newsubsc);
                return newsubsc;
            }
        }
        return null;
    }
    
    @Override
    public boolean readyforOutboundResponse() {
        synchronized(this._subscriptionOfResponse) {
            //  对 outboundResponse 方法加锁
            final Subscription oldsubsc =  this._subscriptionOfResponse.get();
            return (null==oldsubsc ||
                (oldsubsc.isUnsubscribed() && !this._isResponseSended.get()));
        }
    }
    
    @Override
    public Executor requestExecutor() {
        return this._channel.eventLoop();
    }
    
    @Override
    public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed) {
        this._doOnClosed.call(onClosed);
        return this;
    }
    
    @Override
    public void undoOnClosed(final Action1<HttpTrade> onClosed) {
        this._undoOnClosed.call(onClosed);
    }
    
    @Override
    public CachedHttpTrade cached(final int maxBlockSize) {
        if (!this._isRequestReceived.get()) {
            final CachedRequest cached = new CachedRequest(this, maxBlockSize);
            return new CachedHttpTrade() {
    
                /* (non-Javadoc)
                 * @see java.lang.Object#toString()
                 */
                @Override
                public String toString() {
                    StringBuilder builder = new StringBuilder();
                    builder.append("CachedHttpTrade for [")
                        .append(DefaultHttpTrade.this.toString())
                        .append("]");
                    return builder.toString();
                }

                @Override
                public boolean isActive() {
                    return DefaultHttpTrade.this.isActive();
                }
    
                @Override
                public boolean isEndedWithKeepAlive() {
                    return DefaultHttpTrade.this.isEndedWithKeepAlive();
                }
    
                @Override
                public void abort() {
                    DefaultHttpTrade.this.abort();
                }
                
                @Override
                public Observable<? extends HttpObject> inboundRequest() {
                    return cached.request();
                }
    
                @Override
                public Executor requestExecutor() {
                    return DefaultHttpTrade.this.requestExecutor();
                }
    
                @Override
                public Subscription outboundResponse(final Observable<? extends HttpObject> response) {
                    return DefaultHttpTrade.this.outboundResponse(response);
                }
    
                @Override
                public Subscription outboundResponse(
                        final Observable<? extends HttpObject> response,
                        final Action1<Throwable> onError) {
                    return DefaultHttpTrade.this.outboundResponse(response, onError);
                }
                
                @Override
                public boolean readyforOutboundResponse() {
                    return DefaultHttpTrade.this.readyforOutboundResponse();
                }
                
                @Override
                public Object transport() {
                    return DefaultHttpTrade.this.transport();
                }
    
                @Override
                public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed) {
                    return DefaultHttpTrade.this.doOnClosed(onClosed);
                }
    
                @Override
                public void undoOnClosed(Action1<HttpTrade> onClosed) {
                    DefaultHttpTrade.this.undoOnClosed(onClosed);
                }
    
                @Override
                public CachedHttpTrade cached(int maxBlockSize) {
                    return DefaultHttpTrade.this.cached(maxBlockSize);
                }
    
                @Override
                public FullHttpRequest retainFullHttpRequest() {
                    return cached.retainFullHttpRequest();
                }
    
                @Override
                public int currentBlockSize() {
                    return cached.currentBlockSize();
                }
    
                @Override
                public int currentBlockCount() {
                    return cached.currentBlockCount();
                }
    
                @Override
                public int requestHttpObjCount() {
                    return cached.requestHttpObjCount();
                }};
        } else {
            throw new RuntimeException("request has already started!");
        }
    }
    
    private void doClose() {
        this._activeHolder.destroy(this._invokeDoOnClose);
    }
    
    private final ActiveHolder<COWCompositeSupport<Action1<HttpTrade>>> _activeHolder = 
            new ActiveHolder<>(new COWCompositeSupport<Action1<HttpTrade>>());
    
    private final ActionN _doOnClosed = this._activeHolder.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
            @Override
            public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds, final Object...args) {
                oncloseds.addComponent(JOArrays.<Action1<HttpTrade>>takeArgAs(0, args));
            }})
        .submitWhenDestroyed(new ActionN() {
            @Override
            public void call(final Object...args) {
                JOArrays.<Action1<HttpTrade>>takeArgAs(0, args).call(DefaultHttpTrade.this);
            }});
    
    private final ActionN _undoOnClosed = this._activeHolder.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
        @Override
        public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds,final Object...args) {
            oncloseds.removeComponent(JOArrays.<Action1<HttpTrade>>takeArgAs(0, args));
        }});
    
    private final Channel _channel;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = 
            new CopyOnWriteArrayList<>();
    private final AtomicReference<Subscription> _subscriptionOfResponse = 
            new AtomicReference<Subscription>(null);
    
    private final Action1<COWCompositeSupport<Action1<HttpTrade>>> _closeChannelAndInvokeDoOnClose = new Action1<COWCompositeSupport<Action1<HttpTrade>>>() {
        @Override
        public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds) {
            _channel.close();
            _invokeDoOnClose.call(oncloseds);
        }};
        
    private final Action1<COWCompositeSupport<Action1<HttpTrade>>> _invokeDoOnClose = new Action1<COWCompositeSupport<Action1<HttpTrade>>>() {
        @Override
        public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                        _channel, _isResponseCompleted.get(), isEndedWithKeepAlive());
            }
            oncloseds.foreachComponent(new Action1<Action1<HttpTrade>>() {
                @Override
                public void call(final Action1<HttpTrade> onClosed) {
                    try {
                        onClosed.call(DefaultHttpTrade.this);
                    } catch (Exception e) {
                        LOG.warn("exception when trade({}) invoke onClosed({}), detail: {}",
                                DefaultHttpTrade.this, onClosed, ExceptionUtils.exception2detail(e));
                    }
                }});
        }};
        
    private final Action0 _responseOnCompleted = RxActions.toAction0(this._activeHolder.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
            @Override
            public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds, final Object...args) {
                _isResponseSended.compareAndSet(false, true);
                _isResponseCompleted.compareAndSet(false, true);
                _channel.flush();
                try {
                    doClose();
                } catch (Exception e) {
                    LOG.warn("exception when ({}).doCloseTrade, detail:{}",
                        DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                }
            }}));
            
    private final Action1<HttpObject> _responseOnNext = RxActions.toAction1(this._activeHolder.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
            @Override
            public void call(final COWCompositeSupport<Action1<HttpTrade>> oncloseds, final Object...args) {
                _isResponseSended.compareAndSet(false, true);
                _channel.write(ReferenceCountUtil.retain(JOArrays.<HttpObject>takeArgAs(0, args)));
            }}));
    
    private final Action1<Throwable> _responseOnError = new Action1<Throwable>() {
        @Override
        public void call(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            //  default action is abort this trade
            abort();
//            try {
//                //  TODO, ignore onError? for other response Observable
//                doCloseTrade();
//            } catch (Exception e1) {
//                LOG.warn("exception when trade({}).doCloseTrade, detail:{}",
//                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e1));
//            }
        }};
    
    /*
    private final Observer<HttpObject> _responseObserver = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            _responseOnCompleted.call();
//            if (isActive()) {
//                _isResponseSended.compareAndSet(false, true);
//                _channel.flush();
//                try {
//                    doCloseTrade(true);
//                } catch (Exception e) {
//                    LOG.warn("exception when ({}).doCloseTrade, detail:{}",
//                        DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
//                }
//            } else {
//                LOG.warn("onCompleted on closed transport[channel: {}]",
//                        _channel);
//            }
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            try {
                //  TODO, ignore onError? for other response Observable
                doCloseTrade();
            } catch (Exception e1) {
                LOG.warn("exception when trade({}).doCloseTrade, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e1));
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            _responseOnNext.call(msg);
//            if (isActive()) {
//                _isResponseSended.compareAndSet(false, true);
//                _channel.write(ReferenceCountUtil.retain(msg));
//            } else {
//                LOG.warn("sendback msg({}) on closed transport[channel: {}]",
//                    msg, _channel);
//            }
        }
    };
    */
    
    private final Observable<? extends HttpObject> _requestObservable = 
            Observable.create(new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                _requestSubscribers.add(subscriber);
                subscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        _requestSubscribers.remove(subscriber);
                    }}));
            }
        }
    });
    
    private final Observer<HttpObject> _requestRelay = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("trade({}) requestObserver.onCompleted", DefaultHttpTrade.this);
            }
            _isRequestCompleted.set(true);
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        subscriber.onCompleted();
                    } catch (Exception e) {
                        LOG.warn("exception when invoke subscriber({}).onCompleted, detail:{}",
                                subscriber, ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }

        @Override
        public void onNext(final HttpObject httpObject) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("trade({}) requestObserver.onNext, httpobj:{}",
                        DefaultHttpTrade.this, httpObject);
            }
            _isRequestReceived.compareAndSet(false, true);
            if (httpObject instanceof HttpRequest) {
                _isKeepAlive.set(HttpHeaders.isKeepAlive((HttpRequest)httpObject));
            }
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        subscriber.onNext(httpObject);
                    } catch (Exception e) {
                        LOG.warn("exception when invoke subscriber({}).onNext, detail:{}",
                                subscriber, ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }
        
        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({}) requestObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        subscriber.onError(e);
                    } catch (Exception e1) {
                        LOG.warn("exception when invoke subscriber({}).onError, detail:{}",
                                subscriber, ExceptionUtils.exception2detail(e1));
                    }
                }
            }
            doClose();
        }
    };
}
