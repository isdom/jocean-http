package org.jocean.http.server.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ActiveHolder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.Func1_N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
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
import rx.functions.FuncN;
import rx.subscriptions.Subscriptions;

class CachedRequest {
    
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
        trade.inboundRequest().subscribe(this._requestObserver);
        this._trade.doOnClosed(new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                destroy();
            }});
    }
    
    public int currentBlockSize() {
        return this._currentBlockSize;
    }
    
    public int currentBlockCount() {
        return this._currentBlockRef.callWhenActive(new Func1_N<List<HttpContent>, Integer>() {
            @Override
            public Integer call(final List<HttpContent> contents, Object... args) {
                return contents.size();
            }}).callWhenDestroyed(new FuncN<Integer>() {
            @Override
            public Integer call(Object... args) {
                return 0;
            }}).call();
    }
    
    public int requestHttpObjCount() {
        return this._reqHttpObjectsRef.callWhenActive(new Func1_N<List<HttpObject>, Integer>() {
            @Override
            public Integer call(final List<HttpObject> objs, Object... args) {
                return objs.size();
            }}).callWhenDestroyed(new FuncN<Integer>() {
            @Override
            public Integer call(Object... args) {
                return 0;
            }}).call();
    }
    
    private void destroy() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("destroy CachedRequest with {} HttpObject."
                    //  TODO synchronized
                    /*, this._reqHttpObjectsRef.get().size()*/ );
        }
        this._currentBlockRef.destroy(CachedRequest.<HttpContent>buildActionWhenDestroying());
        this._currentBlockSize = 0;
        // release all HttpObjects of request
        this._reqHttpObjectsRef.destroy(CachedRequest.<HttpObject>buildActionWhenDestroying());
    }

    private static <T> Action1<List<T>> buildActionWhenDestroying() {
        return new Action1<List<T>>() {
            @Override
            public void call(final List<T> objs) {
                for (T obj : objs) {
                    ReferenceCountUtil.release(obj);
                }
                objs.clear();
            }};
    }
    
    private HttpContent buildCurrentBlockAndReset() {
        return this._buildCurrentBlockAndResetFunc.call();
    }

    private void updateCurrentBlock(final HttpContent content) {
        this._updateCurrentBlockAction.call(content);
    }

    private void addHttpObjectAndNotifySubscribers(final HttpObject httpobj) {
        this._addHttpObjectAndNotifySubscribersAction.call(httpobj);
    }
    
    public FullHttpRequest retainFullHttpRequest() {
        return this._retainFullHttpRequestFunc.call();
    }
    
    public Observable<HttpObject> request() {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                _trade.requestExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (!subscriber.isUnsubscribed()) {
                            if (null != _error.get()) {
                                try {
                                    subscriber.onError(_error.get());
                                } catch (Throwable e1) {
                                    LOG.warn("exception when request's ({}).onError, detail:{}",
                                        subscriber, ExceptionUtils.exception2detail(e1));
                                }
                                return;
                            }
                            _reqHttpObjectsRef.submitWhenActive(new Action1_N<List<HttpObject>>() {
                                @Override
                                public void call(final List<HttpObject> reqs,final Object...args) {
                                    for (HttpObject httpObj : reqs ) {
                                        subscriber.onNext(httpObj);
                                    }
                                }}).call();
                            if (_isCompleted.get()) {
                                subscriber.onCompleted();
                            }
                            _subscribers.add(subscriber);
                            subscriber.add(Subscriptions.create(new Action0() {
                                @Override
                                public void call() {
                                    _subscribers.remove(subscriber);
                                }}));
                        }
                    }});
            }});
    }
    
    private final HttpTrade _trade;
    
    private final int _maxBlockSize;
    
    private final ActiveHolder<List<HttpContent>> _currentBlockRef = 
            new ActiveHolder<>((List<HttpContent>)new ArrayList<HttpContent>());
    private int _currentBlockSize = 0;
    
    private final ActionN _updateCurrentBlockAction = 
            this._currentBlockRef.submitWhenActive(
            new Action1_N<List<HttpContent>>() {
        @Override
        public void call(final List<HttpContent> currentBlock, final Object...args) {
            final HttpContent httpContent = ActiveHolder.<HttpContent>getArgAs(0, args);
            currentBlock.add(httpContent);
            _currentBlockSize += httpContent.content().readableBytes();
        }});
    
    private final FuncN<HttpContent> _buildCurrentBlockAndResetFunc = 
            this._currentBlockRef.callWhenActive(
            new Func1_N<List<HttpContent>,HttpContent>() {
        @Override
        public HttpContent call(final List<HttpContent> currentBlock, final Object... args) {
            try {
                if (currentBlock.size()>1) {
                    final ByteBuf[] bufs = new ByteBuf[currentBlock.size()];
                    for (int idx = 0; idx<currentBlock.size(); idx++) {
                        bufs[idx] = currentBlock.get(idx).content();
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
                    return currentBlock.get(0);
                }
            } finally {
                currentBlock.clear();
                _currentBlockSize = 0;
            }
        }});
    
    private final ActiveHolder<List<HttpObject>> _reqHttpObjectsRef = 
            new ActiveHolder<>((List<HttpObject>)new ArrayList<HttpObject>());
    
    private final ActionN _addHttpObjectAndNotifySubscribersAction = 
            this._reqHttpObjectsRef.submitWhenActive(
            new Action1_N<List<HttpObject>>() {
        @Override
        public void call(final List<HttpObject> reqs,final Object...args) {
            final HttpObject httpobj = ActiveHolder.<HttpObject>getArgAs(0, args);
            reqs.add(httpobj);
            for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                try {
                    subscriber.onNext(httpobj);
                } catch (Throwable e) {
                    LOG.warn("exception when request's ({}).onNext, detail:{}",
                        subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }});
    
    private final FuncN<FullHttpRequest> _retainFullHttpRequestFunc = 
            this._reqHttpObjectsRef.callWhenActive(
            new Func1_N<List<HttpObject>,FullHttpRequest>() {
        @Override
        public FullHttpRequest call(final List<HttpObject> reqs,final Object...args) {
            if (_isCompleted.get() && reqs.size()>0) {
                if (reqs.get(0) instanceof FullHttpRequest) {
                    return ((FullHttpRequest)reqs.get(0)).retain();
                }
                
                final HttpRequest req = (HttpRequest)reqs.get(0);
                final ByteBuf[] bufs = new ByteBuf[reqs.size()-1];
                for (int idx = 1; idx<reqs.size(); idx++) {
                    bufs[idx-1] = ((HttpContent)reqs.get(idx)).content().retain();
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
        }});
    
    final Observer<HttpObject> _requestObserver = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            if ( _isCompleted.compareAndSet(false, true) ) {
                for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                    try {
                        subscriber.onCompleted();
                    } catch (Throwable e) {
                        LOG.warn("exception when request's ({}).onCompleted, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }
        
        @Override
        public void onError(final Throwable e) {
            if (_error.compareAndSet(null, e)) {
                for (Subscriber<? super HttpObject> subscriber : _subscribers ) {
                    try {
                        subscriber.onError(e);
                    } catch (Throwable e1) {
                        LOG.warn("exception when request's ({}).onError, detail:{}",
                            subscriber, ExceptionUtils.exception2detail(e1));
                    }
                }
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            if (LOG.isDebugEnabled()) {
                if (msg instanceof ByteBufHolder) {
                    LOG.debug("cachedRequest: receive ByteBufHolder's content: {}", 
                            Nettys.dumpByteBufHolder((ByteBufHolder)msg));
                }
            }
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
                    if (_currentBlockSize >= _maxBlockSize) {
                        // build block
                        addHttpObjectAndNotifySubscribers(buildCurrentBlockAndReset());
                    }
                }
            } else {
                addHttpObjectAndNotifySubscribers(ReferenceCountUtil.retain(msg));
            }
        }};
        
    private final List<Subscriber<? super HttpObject>> _subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean _isCompleted = new AtomicBoolean(false);
    private final AtomicReference<Throwable> _error = new AtomicReference<Throwable>(null);
}
