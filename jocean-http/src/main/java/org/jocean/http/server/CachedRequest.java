package org.jocean.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
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
    
    private static int _block_size = 128 * 1024; // 128KB
    static {
        // possible system property for overriding
        final String sizeFromProperty = System.getProperty("org.jocean.http.cachedreq.blocksize");
        if (sizeFromProperty != null) {
            try {
                _block_size = Integer.parseInt(sizeFromProperty);
            } catch (Exception e) {
                System.err.println("Failed to set 'org.jocean.http.cachedreq.blocksize' with value " + sizeFromProperty + " => " + e.getMessage());
            }
        }
    }
    
    private final static int _MAX_BLOCK_SIZE = _block_size;
    
    private static final Logger LOG = LoggerFactory
            .getLogger(CachedRequest.class);
    
    public CachedRequest(final HttpTrade trade) {
        this(trade, _MAX_BLOCK_SIZE);
    }
    
    public CachedRequest(final HttpTrade trade, final int maxBlockSize) {
        this._maxBlockSize = maxBlockSize > 0 ? maxBlockSize : _MAX_BLOCK_SIZE;
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
                if (msg instanceof HttpContent) {
                    if (msg instanceof LastHttpContent) {
                        if (_currentBlockSize > 0) {
                            // build block left
                            addHttpObjectAndNotifySubscribers(buildCurrentBlockAndReset());
                        }
                        // direct add last HttpContent
                        addHttpObjectAndNotifySubscribers(ReferenceCountUtil.retain(msg));
                    } else {
                        updateCurrentBlock(ReferenceCountUtil.retain((HttpContent)msg));
                        if (_currentBlockSize > _maxBlockSize) {
                            // build block
                            addHttpObjectAndNotifySubscribers(buildCurrentBlockAndReset());
                        }
                    }
                } else {
                    addHttpObjectAndNotifySubscribers(ReferenceCountUtil.retain(msg));
                }
            }});
    }
    
    private HttpContent buildCurrentBlockAndReset() {
        try {
            if (this._currentBlock.size()>1) {
                final ByteBuf[] bufs = new ByteBuf[this._currentBlock.size()];
                for (int idx = 0; idx<this._currentBlock.size(); idx++) {
                    bufs[idx] = this._currentBlock.get(idx).content();
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("build block: assemble {} HttpContent to composite content with size {} KB",
                            bufs.length, (float)_currentBlockSize / 1024f);
                }
                return new DefaultHttpContent(Unpooled.wrappedBuffer(bufs.length, bufs));
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("build block: only one HttpContent with {} KB to build block, so pass through",
                            (float)_currentBlockSize / 1024f);
                }
                return this._currentBlock.get(0);
            }
        } finally {
            this._currentBlock.clear();
            this._currentBlockSize = 0;
        }
    }

    private void updateCurrentBlock(final HttpContent content) {
        this._currentBlock.add(content);
        this._currentBlockSize += content.content().readableBytes();
    }

    private void addHttpObjectAndNotifySubscribers(final HttpObject httpobj) {
        this._reqHttpObjects.add(httpobj);
        for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
            try {
                subscriber.onNext(httpobj);
            } catch (Throwable e) {
                LOG.warn("exception when request's ({}).onNext, detail:{}",
                    subscriber, ExceptionUtils.exception2detail(e));
            }
        }
    }

    public void destroy() {
        this._trade.requestExecutor().execute(new Runnable() {
            @Override
            public void run() {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("destroy CachedRequest with {} HttpObject.",
                            _reqHttpObjects.size());
                }
                clearHttpObjs(_currentBlock);
                // release all HttpObjects of request
                clearHttpObjs(_reqHttpObjects);
            }});
    }
    
    private static void clearHttpObjs(final List<? extends HttpObject> httpobjs) {
        for (HttpObject obj : httpobjs) {
            ReferenceCountUtil.release(obj);
        }
        httpobjs.clear();
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
    
    public Observable<HttpObject> request() {
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
    
    private final int _maxBlockSize;
    
    private final List<HttpContent> _currentBlock = new ArrayList<>();
    private int _currentBlockSize = 0;
    
    private final List<HttpObject> _reqHttpObjects = new ArrayList<>();
    private final List<Subscriber<? super HttpObject>> _subscribers = new CopyOnWriteArrayList<>();
    private boolean _isCompleted = false;
    private volatile Throwable _error = null;
}
