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
import org.jocean.http.server.impl.DefaultHttpServer.ChannelRecycler;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
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
class DefaultHttpTrade implements HttpServer.HttpTrade, Nettys.OnHttpObject {
    
    private static final Subscription[] EMPTY_SUBSCRIPTIONS = new Subscription[0];

    @Override
    public String toString() {
        return "DefaultHttpTrade [channel=" + _channel + ", request's subscribers.size="
                + _subscribers.size() + ", isKeepAlive=" + _isKeepAlive + "]";
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    public DefaultHttpTrade(
            final Channel channel, 
            final ChannelRecycler channelRecycler,
            final HandlerBuilder builder,
            final Feature... features) {
        this._channelRecycler = channelRecycler;
        this._channel = channel;
        final OnHttpObjectAware onHttpObjectAware = 
                InterfaceUtils.compositeIncludeType(OnHttpObjectAware.class, (Object[])features);
        if (null!=onHttpObjectAware) {
            onHttpObjectAware.setOnHttpObject(this);
        }
        
        final List<Subscription> subscriptions = new ArrayList<>();
        for (Feature feature : features) {
            final Subscription subscription = 
                    RxNettys.buildHandlerReleaser(channel, feature.call(builder, channel.pipeline()));
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
        return this._channel;
    }
    
    @Override
    public void onHttpObject(final HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            this._isKeepAlive = HttpHeaders.isKeepAlive((HttpRequest)httpObject);
        }
        for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onNext(httpObject);
                    if (httpObject instanceof LastHttpContent) {
                        subscriber.onCompleted();
                    }
                } catch (Exception e) {
                    LOG.warn("exception when invoke subscriber({}).onNext/onCompleted, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("trade({}).onError, detail:{}",
                this, ExceptionUtils.exception2detail(e));
        for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
            try {
                subscriber.onError(e);
            } catch (Exception e1) {
                LOG.warn("exception when invoke subscriber({}).onError, detail:{}",
                        subscriber, ExceptionUtils.exception2detail(e1));
            }
        }
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
        
        return new Subscriber<HttpObject>() {

            @Override
            public void onCompleted() {
                _removeHandlers.unsubscribe();
                _channelRecycler.onResponseCompleted(_channel, _isKeepAlive);
            }

            @Override
            public void onError(final Throwable e) {
                LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                        DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                _removeHandlers.unsubscribe();
                _channelRecycler.onResponseCompleted(_channel, _isKeepAlive);
            }

            @Override
            public void onNext(final HttpObject msg) {
                _channel.write(ReferenceCountUtil.retain(msg));
                //  TODO check write future's isSuccess
            }};
    }
    
    private final Channel _channel;
    private final List<Subscriber<? super HttpObject>> _subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean _isKeepAlive = false;
    private final ChannelRecycler _channelRecycler;
    private final Subscription _removeHandlers;
    
    private final OnSubscribe<HttpObject> _onSubscribeRequest = new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                _subscribers.add(subscriber);
                subscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        _subscribers.remove(subscriber);
                    }}));
            }
        }
    };
}
