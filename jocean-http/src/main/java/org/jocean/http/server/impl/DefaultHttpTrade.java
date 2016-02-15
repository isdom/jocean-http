/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.jocean.http.server.HttpServer;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action0;
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
                .append(_requestSubscribers.size()).append(", isKeepAlive=")
                .append(_requestExecutor).append(", transport=")
                .append(_transport).append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    public DefaultHttpTrade(
            final OutputChannel output, 
            final Object    transport,
            final Executor  requestExecutor) {
        this._output = output;
        this._transport = transport;
        this._requestExecutor = requestExecutor;
    }

    Observer<HttpObject> requestObserver() {
        return this._requestObserver;
    }
    
    @Override
    public Object transport() {
        return this._transport;
    }
    
    @Override
    public Observable<? extends HttpObject> request() {
        return Observable.create(this._onSubscribeRequest);
    }

    @Override
    public Executor requestExecutor() {
        return this._requestExecutor;
    }
    
    @Override
    public Observer<HttpObject> responseObserver() {
        return this._responseObserver;
    }
    
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = new CopyOnWriteArrayList<>();
    private final Executor _requestExecutor;
    private final Object   _transport;
    private final OutputChannel _output;
    
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
        }};
        
    final Subscriber<HttpObject> _responseObserver = new Subscriber<HttpObject>() {

        @Override
        public void onCompleted() {
            try {
                _output.onTradeCompleted();
            } catch (Exception e) {
                LOG.warn("exception when ({}).onResponseCompleted, detail:{}",
                        _output, ExceptionUtils.exception2detail(e));
            }
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            try {
                _output.onTradeCompleted();
            } catch (Exception e1) {
                LOG.warn("exception when ({}).onResponseCompleted, detail:{}",
                        _output, ExceptionUtils.exception2detail(e1));
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            try {
                _output.output(msg);
            } catch (Exception e) {
                LOG.warn("exception when ({}).output message({}), detail:{}",
                        _output, msg, ExceptionUtils.exception2detail(e));
            }
            //  TODO check write future's isSuccess
        }};
}
