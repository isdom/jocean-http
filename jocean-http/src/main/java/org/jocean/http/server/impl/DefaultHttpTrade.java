/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.server.CachedRequest;
import org.jocean.http.server.HttpServer.CachedHttpTrade;
import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StatefulRef;
import org.jocean.idiom.rx.Action1_N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
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
                .append(_requestSubscribers.size()).append(", isRequestCompleted=")
                .append(_isRequestCompleted.get()).append(", isKeepAlive=")
                .append(_isKeepAlive.get()).append(", isClosed=")
                .append(_isActive.get()).append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    DefaultHttpTrade(
            final Channel channel, 
            final Observable<HttpObject> requestObservable) {
        this._channel = channel;
        requestObservable.subscribe(this._requestObserver);
    }

    @Override
    public boolean isActive() {
        return this._isActive.get();
    }

    @Override
    public boolean isEndedWithKeepAlive() {
        return (this._isRequestCompleted.get() 
            && this._isResponseCompleted.get()
            && this._isKeepAlive.get());
    }

    @Override
    public Object transport() {
        return this._channel;
    }
    
    @Override
    public Observable<? extends HttpObject> request() {
        return Observable.create(this._onSubscribeRequest);
    }

    @Override
    public Executor requestExecutor() {
        return this._channel.eventLoop();
    }
    
    @Override
    public Observer<HttpObject> responseObserver() {
        return this._responseObserver;
    }
    
    @Override
    public HttpTrade addOnTradeClosed(final Action1<HttpTrade> onTradeClosed) {
        this._addOnTradeClosed.call(onTradeClosed);
        return this;
    }
    
    @Override
    public void removeOnTradeClosed(final Action1<HttpTrade> onTradeClosed) {
        this._removeOnTradeClosed.call();
    }
    
    @Override
    public CachedHttpTrade cached(final int maxBlockSize) {
        final CachedRequest cached = new CachedRequest(this, maxBlockSize);
        return new CachedHttpTrade() {

            @Override
            public boolean isActive() {
                return DefaultHttpTrade.this.isActive();
            }

            @Override
            public boolean isEndedWithKeepAlive() {
                return DefaultHttpTrade.this.isEndedWithKeepAlive();
            }

            @Override
            public Observable<? extends HttpObject> request() {
                return cached.request();
            }

            @Override
            public Executor requestExecutor() {
                return DefaultHttpTrade.this.requestExecutor();
            }

            @Override
            public Observer<HttpObject> responseObserver() {
                return DefaultHttpTrade.this.responseObserver();
            }

            @Override
            public Object transport() {
                return DefaultHttpTrade.this.transport();
            }

            @Override
            public HttpTrade addOnTradeClosed(
                    final Action1<HttpTrade> onTradeClosed) {
                return DefaultHttpTrade.this.addOnTradeClosed(onTradeClosed);
            }

            @Override
            public void removeOnTradeClosed(Action1<HttpTrade> onTradeClosed) {
                DefaultHttpTrade.this.removeOnTradeClosed(onTradeClosed);
            }

            @Override
            public CachedHttpTrade cached(int maxBlockSize) {
                return DefaultHttpTrade.this.cached(maxBlockSize);
            }

            @Override
            public FullHttpRequest retainFullHttpRequest() {
                return cached.retainFullHttpRequest();
            }};
    }
    
    private final StatefulRef<COWCompositeSupport<Action1<HttpTrade>>> _onTradeClosedActionsRef = 
            new StatefulRef<>(new COWCompositeSupport<Action1<HttpTrade>>());
    
    private final ActionN _addOnTradeClosed = this._onTradeClosedActionsRef.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
            @Override
            public void call(final COWCompositeSupport<Action1<HttpTrade>> actions, final Object...args) {
                actions.addComponent(StatefulRef.<Action1<HttpTrade>>getArgAs(0, args));
            }})
        .submitWhenDestroyed(new ActionN() {
            @Override
            public void call(final Object...args) {
                StatefulRef.<Action1<HttpTrade>>getArgAs(0, args).call(DefaultHttpTrade.this);
            }});
    
    private final ActionN _removeOnTradeClosed = this._onTradeClosedActionsRef.submitWhenActive(
            new Action1_N<COWCompositeSupport<Action1<HttpTrade>>>() {
        @Override
        public void call(final COWCompositeSupport<Action1<HttpTrade>> actions,final Object...args) {
            actions.removeComponent(StatefulRef.<Action1<HttpTrade>>getArgAs(0, args));
        }});
    
    private final Channel _channel;
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final AtomicBoolean _isActive = new AtomicBoolean(true);
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = new CopyOnWriteArrayList<>();
    
    private final Observer<HttpObject> _responseObserver = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            try {
                doCloseTrade(true);
            } catch (Exception e) {
                LOG.warn("exception when ({}).onTradeClosed, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            }
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            try {
                doCloseTrade(false);
            } catch (Exception e1) {
                LOG.warn("exception when trade({}).onTradeClosed, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e1));
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            if (isActive()) {
                if (msg instanceof LastHttpContent) {
                    _channel.writeAndFlush(ReferenceCountUtil.retain(msg));
                } else {
                    _channel.write(ReferenceCountUtil.retain(msg));
                }
            } else {
                LOG.warn("sendback msg({}) on closed transport[channel: {}]",
                    msg, _channel);
            }
        }
    };
    
    private final OnSubscribe<HttpObject> _onSubscribeRequest = new OnSubscribe<HttpObject>() {
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
    };
    
    private final Observer<HttpObject> _requestObserver = new Observer<HttpObject>() {

        @Override
        public void onCompleted() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("trade({}) requestObserver.onCompleted", DefaultHttpTrade.this);
            }
            _isRequestCompleted.set(true);
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                try {
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onCompleted();
                    }
                } catch (Exception e) {
                    LOG.warn("exception when invoke subscriber({}).onCompleted, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }

        @Override
        public void onNext(final HttpObject httpObject) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("trade({}) requestObserver.onNext, httpobj:{}",
                        DefaultHttpTrade.this, httpObject);
            }
            if (httpObject instanceof HttpRequest) {
                _isKeepAlive.set(HttpHeaders.isKeepAlive((HttpRequest)httpObject));
            }
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                try {
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onNext(httpObject);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when invoke subscriber({}).onNext, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
        
        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({}) requestObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            for (Subscriber<? super HttpObject> subscriber : _requestSubscribers) {
                try {
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onError(e);
                    }
                } catch (Exception e1) {
                    LOG.warn("exception when invoke subscriber({}).onError, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e1));
                }
            }
            doCloseTrade(false);
        }
    };
        
    private boolean checkActiveAndTryClose() {
        return this._isActive.compareAndSet(true, false);
    }
    
    private synchronized void doCloseTrade(final boolean isResponseCompleted) {
        if (checkActiveAndTryClose()) {
            this._isResponseCompleted.set(isResponseCompleted);
            if (LOG.isDebugEnabled()) {
                LOG.debug("invoke doCloseTrade on active trade[channel: {}] with isEndedWithKeepAlive({})", 
                        this._channel, this.isEndedWithKeepAlive());
            }
            this._onTradeClosedActionsRef.destroy(new Action1<COWCompositeSupport<Action1<HttpTrade>>>() {
                @Override
                public void call(final COWCompositeSupport<Action1<HttpTrade>> actions) {
                    actions.foreachComponent(new Action1<Action1<HttpTrade>>() {
                        @Override
                        public void call(final Action1<HttpTrade> onTradeClosed) {
                            onTradeClosed.call(DefaultHttpTrade.this);
                        }});
                }});
        } else {
            LOG.warn("invoke onTradeClosed on closed trade[channel: {}]", this._channel);
        }
    }
}
