package org.jocean.http.server.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.util.Nettys;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
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
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

class HttpObjectHolder {
    private static int _block_size = 128 * 1024; // 128KB
    static {
        // possible system property for overriding
        final String sizeFromProperty = System.getProperty("org.jocean.http.httpholder.blocksize");
        if (sizeFromProperty != null) {
            try {
                _block_size = Integer.parseInt(sizeFromProperty);
            } catch (Exception e) {
                System.err.println("Failed to set 'org.jocean.http.httpholder.blocksize' with value " + sizeFromProperty + " => " + e.getMessage());
            }
        }
    }
    
    private final static int _MAX_BLOCK_SIZE = _block_size;
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpObjectHolder.class);
    
    private final FuncSelector<HttpObjectHolder> _selector = 
            new FuncSelector<>(this);
    
    public HttpObjectHolder(final int maxBlockSize) {
        //  0 : using _MAX_BLOCK_SIZE 
        //  -1 : disable assemble piece to a big block feature
        this._enableAssemble = maxBlockSize >= 0;
        this._maxBlockSize = maxBlockSize > 0 ? maxBlockSize : _MAX_BLOCK_SIZE;
    }
    
    //  TODO 2016-06-09 
    //  remove from Holder and move to Common Utils class 
    public Func0<FullHttpRequest> retainFullHttpRequest() {
        return this._funcRetainFullHttpRequest;
    }
    
    private final Func0<FullHttpRequest> _funcRetainFullHttpRequest = 
        RxFunctions.toFunc0(
            this._selector.callWhenActive(
                    RxFunctions.<HttpObjectHolder, FullHttpRequest>toFunc1_N(
                            HttpObjectHolder.class, "doRetainFullHttpRequest")));
    
    @SuppressWarnings("unused")
    private FullHttpRequest doRetainFullHttpRequest() {
        if (this._isCompleted.get() && this._cachedHttpObjects.size()>0) {
            if (this._cachedHttpObjects.get(0) instanceof FullHttpRequest) {
                return ((FullHttpRequest)this._cachedHttpObjects.get(0)).retain();
            }
            
            final HttpRequest req = (HttpRequest)this._cachedHttpObjects.get(0);
            final ByteBuf[] bufs = new ByteBuf[this._cachedHttpObjects.size()-1];
            for (int idx = 1; idx<this._cachedHttpObjects.size(); idx++) {
                bufs[idx-1] = ((HttpContent)this._cachedHttpObjects.get(idx)).content().retain();
            }
            final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                    req.getProtocolVersion(), 
                    req.getMethod(), 
                    req.getUri(), 
                    Unpooled.wrappedBuffer(bufs));
            fullreq.headers().add(req.headers());
            //  ? need update Content-Length header field ?
            return fullreq;
        } else {
            return null;
        }
    }
    
    public Action0 release() {
        return new Action0() {
        @Override
        public void call() {
            _selector.destroy(RxActions.toAction1_N(HttpObjectHolder.class, "doRelease"));
        }};
    }
    
    @SuppressWarnings("unused")
    private void doRelease() {
        releaseReferenceCountedList(this._currentBlock);
        releaseReferenceCountedList(this._cachedHttpObjects);
        this._currentBlockSize = 0;
    }

    private static <T> void releaseReferenceCountedList(final List<T> objs) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("release {} contain {} element.", objs, objs.size());
        }
        for (T obj : objs) {
            ReferenceCountUtil.release(obj);
        }
        objs.clear();
    }
    
    public int currentBlockSize() {
        return this._currentBlockSize;
    }
    
    public int currentBlockCount() {
        return this._selector.callWhenActive(new Func1_N<HttpObjectHolder, Integer>() {
            @Override
            public Integer call(final HttpObjectHolder holder, final Object... args) {
                return holder._currentBlock.size();
            }}).callWhenDestroyed(new Func1_N<HttpObjectHolder, Integer>() {
            @Override
            public Integer call(final HttpObjectHolder holder, final Object... args) {
                return 0;
            }}).call();
    }
    
    public int cachedHttpObjectCount() {
        return this._selector.callWhenActive(new Func1_N<HttpObjectHolder, Integer>() {
            @Override
            public Integer call(final HttpObjectHolder holder, final Object... args) {
                return holder._cachedHttpObjects.size();
            }}).callWhenDestroyed(new Func1_N<HttpObjectHolder, Integer>() {
            @Override
            public Integer call(final HttpObjectHolder holder, final Object... args) {
                return 0;
            }}).call();
    }
    
    public Func1<HttpObject, Observable<? extends HttpObject>> assembleAndHold() {
        return new Func1<HttpObject, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final HttpObject msg) {
                if (LOG.isDebugEnabled()) {
                    if (msg instanceof ByteBufHolder) {
                        LOG.debug("HttpObjectHolder: receive ByteBufHolder's content: {}", 
                                Nettys.dumpByteBufHolder((ByteBufHolder)msg));
                    }
                }
                if (_enableAssemble && (msg instanceof HttpContent)) {
                    if (msg instanceof LastHttpContent) {
                        return asObservable(retainAnyBlockLeft(), retainAndHoldHttpObject(msg));
                    } else {
                        return assembleAndReturnObservable((HttpContent)msg);
                    }
                } else {
                    return asObservable(retainAndHoldHttpObject(msg));
                }
            }};
    }

    private HttpObject retainAnyBlockLeft() {
        return (this._currentBlockSize > 0) ? retainCurrentBlockAndReset() : null;
    }

    private Observable<? extends HttpObject> assembleAndReturnObservable(
            final HttpContent msg) {
        retainAndUpdateCurrentBlock(msg);
        return (this._currentBlockSize >= this._maxBlockSize) 
                ? asObservable(retainCurrentBlockAndReset())
                : Observable.<HttpObject>empty();
    }

    private static Observable<HttpObject> asObservable(final HttpObject... httpObjects) {
        final List<HttpObject> src = new ArrayList<>();
        for (HttpObject obj : httpObjects) {
            if (null != obj) {
                src.add(obj);
            }
        }
        return (!src.isEmpty()) ? Observable.from(src) : Observable.<HttpObject>empty();
    }

    private void retainAndUpdateCurrentBlock(final HttpContent content) {
        this._actionRetainAndUpdateCurrentBlock.call(content);
    }

    private final Action1<HttpContent> _actionRetainAndUpdateCurrentBlock = 
        RxActions.toAction1(
            this._selector.submitWhenActive(
                RxActions.toAction1_N(HttpObjectHolder.class, "doRetainAndUpdateCurrentBlock")));

    @SuppressWarnings("unused")
    private void doRetainAndUpdateCurrentBlock(final HttpContent content) {
        if (null != content) {
            this._currentBlock.add(ReferenceCountUtil.retain(content));
            this._currentBlockSize += content.content().readableBytes();
        }
    }
    
    private HttpObject retainAndHoldHttpObject(final HttpObject httpobj) {
        return this._funcRetainAndHoldHttpObject.call(httpobj);
    }
    
    private final Func1<HttpObject, HttpObject> _funcRetainAndHoldHttpObject = 
        RxFunctions.toFunc1(
            this._selector.callWhenActive(
                RxFunctions.<HttpObjectHolder, HttpObject>toFunc1_N(
                        HttpObjectHolder.class, "doRetainAndHoldHttpObject")));
    
    @SuppressWarnings("unused")
    private HttpObject doRetainAndHoldHttpObject(final HttpObject httpobj) {
        if (null != httpobj) {
            this._cachedHttpObjects.add(ReferenceCountUtil.retain(httpobj));
            if (httpobj instanceof LastHttpContent) {
                this._isCompleted.set(true);
            }
        }
        return httpobj;
    }
    
    private HttpObject retainCurrentBlockAndReset() {
        final HttpContent content = this._funcBuildCurrentBlockAndReset.call();
        try {
            return retainAndHoldHttpObject(content);
        } finally {
            if (null!=content) {
                ReferenceCountUtil.release(content);
            }
        }
    }

    private final Func0<HttpContent> _funcBuildCurrentBlockAndReset = 
        RxFunctions.toFunc0(
            this._selector.callWhenActive(
                    RxFunctions.<HttpObjectHolder, HttpContent>toFunc1_N(
                            HttpObjectHolder.class, "doBuildCurrentBlockAndReset")));

    @SuppressWarnings("unused")
    private HttpContent doBuildCurrentBlockAndReset() {
        try {
            if (this._currentBlock.size()>1) {
                final ByteBuf[] bufs = new ByteBuf[this._currentBlock.size()];
                for (int idx = 0; idx<this._currentBlock.size(); idx++) {
                    bufs[idx] = this._currentBlock.get(idx).content();
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("build block: assemble {} HttpContent to composite content with size {} KB",
                            bufs.length, (float)this._currentBlockSize / 1024f);
                }
                return new DefaultHttpContent(Unpooled.wrappedBuffer(bufs.length, bufs));
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("build block: only one HttpContent with {} KB to build block, so pass through",
                            (float)this._currentBlockSize / 1024f);
                }
                return this._currentBlock.get(0);
            }
        } finally {
            this._currentBlock.clear();
            this._currentBlockSize = 0;
        }
    }
    
    private final boolean _enableAssemble;
    
    private final int _maxBlockSize;
    
    private final List<HttpContent> _currentBlock = new ArrayList<HttpContent>();
    
    private volatile int _currentBlockSize = 0;

    private final List<HttpObject> _cachedHttpObjects = new ArrayList<>();
    
    private final AtomicBoolean _isCompleted = new AtomicBoolean(false);
}
