/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;
import org.jocean.http.server.HttpServer;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade implements HttpServer.HttpTrade {
    
    private static final Subscription[] EMPTY_SUBSCRIPTIONS = new Subscription[0];

    @Override
    public String toString() {
        return "DefaultHttpTrade [transport=" + _transport + ", request's subscribers.size="
                + _requestSubscribers.size() + ", isKeepAlive=" + _isKeepAlive + "]";
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    public DefaultHttpTrade(
            final OutputChannel output, 
            final Object    transport,
            final Executor  requestExecutor,
            final HandlerBuilder builder,
            final Feature... features) {
        this._output = output;
        this._transport = transport;
        this._requestExecutor = requestExecutor;
        final HttpObjectObserverAware onHttpObjectAware = 
                InterfaceUtils.compositeIncludeType(HttpObjectObserverAware.class, (Object[])features);
        if (null!=onHttpObjectAware) {
            onHttpObjectAware.setHttpObjectObserver(this._requestObserver);
        }
        
        final List<Subscription> subscriptions = new ArrayList<>();
        for (Feature feature : features) {
            final Subscription subscription = 
                    RxNettys.buildHandlerReleaser(this._output.channel(), 
                            feature.call(builder, this._output.channel().pipeline()));
            if (null != subscription) {
                subscriptions.add(subscription);
            }
        }
        this._removeHandlers = Subscriptions.from(subscriptions.toArray(EMPTY_SUBSCRIPTIONS));
        //  TODO, unsubscribe execute in eventloop?
        // RxNettys.removeHandlersSubscription(channel, diff.call());
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
        return _responseObserver;
    }
    
    //  TODO: dont't usin channel direct, wrap it and recycler
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = new CopyOnWriteArrayList<>();
    private volatile boolean _isKeepAlive = false;
    private final Subscription _removeHandlers;
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
            if (httpObject instanceof HttpRequest) {
                _isKeepAlive = HttpHeaders.isKeepAlive((HttpRequest)httpObject);
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
            _removeHandlers.unsubscribe();
            _output.onResponseCompleted(_isKeepAlive);
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            _removeHandlers.unsubscribe();
            try {
                _output.onResponseCompleted(_isKeepAlive);
            } catch (Exception e1) {
                LOG.warn("exception when ({}).onResponseCompleted with keepAlive({}), detail:{}",
                        _output, _isKeepAlive, ExceptionUtils.exception2detail(e1));
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
