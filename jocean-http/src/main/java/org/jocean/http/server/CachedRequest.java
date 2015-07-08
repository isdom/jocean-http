package org.jocean.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.OneshotSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;

public class CachedRequest {
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRequest.class);
    
    public CachedRequest(final HttpTrade trade) {
        this._trade = trade;
        trade.request().subscribe(new Observer<HttpObject>() {
            @Override
            public void onCompleted() {
                _isCompleted = true;
                for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                    try {
                        subscriber.onCompleted();
                    } catch (Throwable e) {
                        LOG.warn("exception when request's ({}).onCompleted, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                    }
                }
            }
            
            @Override
            public void onError(final Throwable e) {
                _error = e;
                for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                    try {
                        subscriber.onError(e);
                    } catch (Throwable e1) {
                        LOG.warn("exception when request's ({}).onError, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e1));
                    }
                }
            }

            @Override
            public void onNext(final HttpObject msg) {
                _reqHttpObjects.add(ReferenceCountUtil.retain(msg));
                for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                    try {
                        subscriber.onNext(msg);
                    } catch (Throwable e) {
                        LOG.warn("exception when request's ({}).onNext, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                    }
                }
            }});
    }
    
    public void destroy() {
        this._trade.requestExecutor().execute(new Runnable() {
            @Override
            public void run() {
                // release all HttpObjects of request
                for (HttpObject obj : _reqHttpObjects) {
                    ReferenceCountUtil.release(obj);
                }
                _reqHttpObjects.clear();
            }});
    }
    
    public FullHttpRequest retainFullHttpRequest() {
        if (this._isCompleted && this._reqHttpObjects.size()>0) {
            if (this._reqHttpObjects.get(0) instanceof FullHttpRequest) {
                return ((FullHttpRequest)this._reqHttpObjects.get(0)).retain();
            }
            
            final HttpRequest req = (HttpRequest)this._reqHttpObjects.get(0);
            final ByteBuf[] bufs = new ByteBuf[this._reqHttpObjects.size()-1];
            for (int idx = 1; idx<this._reqHttpObjects.size(); idx++) {
                bufs[idx-1] = ((HttpContent)this._reqHttpObjects.get(idx)).content().retain();
            }
            final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                    req.getProtocolVersion(), 
                    req.getMethod(), 
                    req.getUri(), 
                    Unpooled.wrappedBuffer(bufs));
            fullreq.headers().add(req.headers());
            return fullreq;
        } else {
            return null;
        }
    }
    
    public Observable<? extends HttpObject> request() {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                _trade.requestExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (!subscriber.isUnsubscribed()) {
                            if (null != _error) {
                                try {
                                    subscriber.onError(_error);
                                } catch (Throwable e1) {
                                    LOG.warn("exception when request's ({}).onError, detail:{}",
                                        subscriber, ExceptionUtils.exception2detail(e1));
                                }
                                return;
                            }
                            for (HttpObject httpObj : _reqHttpObjects ) {
                                subscriber.onNext(httpObj);
                            }
                            if (_isCompleted) {
                                subscriber.onCompleted();
                            }
                            _subscribers.add(subscriber);
                            subscriber.add(new OneshotSubscription() {
                                @Override
                                protected void doUnsubscribe() {
                                    _subscribers.remove(subscriber);
                                }});
                        }
                    }});
            }});
    }
    
    private final HttpTrade _trade;
    private final List<HttpObject> _reqHttpObjects = new ArrayList<>();
    private final List<Subscriber<? super HttpObject>> _subscribers = new CopyOnWriteArrayList<>();
    private boolean _isCompleted = false;
    private volatile Throwable _error = null;
}
