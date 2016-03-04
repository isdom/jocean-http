/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.server.HttpServer;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade implements HttpServer.HttpTrade {
    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [request's subscribers.size=")
                .append(_requestSubscribers.size()).append(", isRequestCompleted=")
                .append(_isRequestCompleted.get()).append(", isKeepAlive=")
                .append(_isKeepAlive.get()).append(", isClosed=")
                .append(_isClosed.get()).append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    public DefaultHttpTrade(
            final Channel channel, 
            final Action1<Boolean> onTradeClosed) {
        this._channel = channel;
        this._onTradeClosed = onTradeClosed;
    }

    Observer<HttpObject> requestObserver() {
        return this._requestObserver;
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
    
    private final Channel _channel;
    private final Action1<Boolean> _onTradeClosed;
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final AtomicBoolean _isClosed = new AtomicBoolean(false);
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
                LOG.warn("exception when ({}).onTradeClosed, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e1));
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            if (isActive()) {
                _channel.write(ReferenceCountUtil.retain(msg));
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
                LOG.debug("trade({}) requestObserver.onCompleted", this);
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
                        this, httpObject);
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
                    this, ExceptionUtils.exception2detail(e));
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
        
    private boolean isActive() {
        return !this._isClosed.get();
    }

    private boolean checkActiveAndTryClose() {
        return this._isClosed.compareAndSet(false, true);
    }
    
    private synchronized void doCloseTrade(final boolean isResponseCompleted) {
        if (checkActiveAndTryClose()) {
            final boolean canReuseChannel = 
                    this._isRequestCompleted.get() 
                    && isResponseCompleted 
                    && this._isKeepAlive.get();
            this._onTradeClosed.call(canReuseChannel);
            if (LOG.isDebugEnabled()) {
                LOG.debug("invoke onTradeClosed on active trade[channel: {}] with canReuseChannel({})", 
                        this._channel, canReuseChannel);
            }
        } else {
            LOG.warn("invoke onTradeClosed on closed trade[channel: {}]", this._channel);
        }
    }
}
