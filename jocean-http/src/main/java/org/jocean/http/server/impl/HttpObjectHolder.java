package org.jocean.http.server.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.util.Nettys;
import org.jocean.idiom.ActiveHolder;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.Action1_N;
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
import rx.functions.FuncN;

class HttpObjectHolder {
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
            .getLogger(HttpObjectHolder.class);
    
    public HttpObjectHolder(final int maxBlockSize) {
        this._maxBlockSize = maxBlockSize > 0 ? maxBlockSize : _MAX_BLOCK_SIZE;
    }
    
    public Action0 destroy() {
        return new Action0() {
        @Override
        public void call() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("destroy HttpObjectCompositor with {} HttpObject."
                        //  TODO synchronized
                        /*, this._reqHttpObjectsRef.get().size()*/ );
                _currentBlockRef.destroy(HttpObjectHolder.<HttpContent>buildActionWhenDestroying());
                _currentBlockSize = 0;
                // release all HttpObjects of request
                _reqHttpObjectsRef.destroy(HttpObjectHolder.<HttpObject>buildActionWhenDestroying());
            }
        }};
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
    
    public int currentBlockSize() {
        return this._currentBlockSize;
    }
    
    public int currentBlockCount() {
        return this._currentBlockRef.callWhenActive(new Func1_N<List<HttpContent>, Integer>() {
            @Override
            public Integer call(final List<HttpContent> contents, final Object... args) {
                return contents.size();
            }}).callWhenDestroyed(new FuncN<Integer>() {
            @Override
            public Integer call(final Object... args) {
                return 0;
            }}).call();
    }
    
    public int requestHttpObjCount() {
        return this._reqHttpObjectsRef.callWhenActive(new Func1_N<List<HttpObject>, Integer>() {
            @Override
            public Integer call(final List<HttpObject> objs, final Object... args) {
                return objs.size();
            }}).callWhenDestroyed(new FuncN<Integer>() {
            @Override
            public Integer call(final Object... args) {
                return 0;
            }}).call();
    }
    
    public Func1<HttpObject, Observable<? extends HttpObject>> composite() {
        return new Func1<HttpObject, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final HttpObject msg) {
                if (LOG.isDebugEnabled()) {
                    if (msg instanceof ByteBufHolder) {
                        LOG.debug("HttpObjectCompositor: receive ByteBufHolder's content: {}", 
                                Nettys.dumpByteBufHolder((ByteBufHolder)msg));
                    }
                }
                if (msg instanceof HttpContent) {
                    if (msg instanceof LastHttpContent) {
                        HttpObject httpObject = null;
                        _isCompleted.set(true);
                        if (_currentBlockSize > 0) {
                            // build block left
                            httpObject = retainCurrentBlockAndReset();
                        }
                        return asObservable(httpObject, retainAndHoldHttpObject(msg));
                    } else {
                        retainAndUpdateCurrentBlock((HttpContent)msg);
                        if (_currentBlockSize >= _maxBlockSize) {
                            return asObservable(retainCurrentBlockAndReset());
                        }
                    }
                    return Observable.<HttpObject>empty();
                } else {
                    return asObservable(retainAndHoldHttpObject(msg));
                }
            }};
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

    private HttpObject retainAndHoldHttpObject(final HttpObject httpobj) {
        return this._funcRetainAndHoldHttpObject.call(httpobj);
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

    public Func0<FullHttpRequest> retainFullHttpRequest() {
        return this._funcRetainFullHttpRequest;
    }
    
    private final int _maxBlockSize;
    
    private final ActiveHolder<List<HttpContent>> _currentBlockRef = 
            new ActiveHolder<>((List<HttpContent>)new ArrayList<HttpContent>());
    private int _currentBlockSize = 0;

    private final ActiveHolder<List<HttpObject>> _reqHttpObjectsRef = 
            new ActiveHolder<>((List<HttpObject>)new ArrayList<HttpObject>());
    
    private final AtomicBoolean _isCompleted = new AtomicBoolean(false);
    
    private final Action1<HttpContent> _actionRetainAndUpdateCurrentBlock = 
            RxActions.toAction1(
            this._currentBlockRef.submitWhenActive(
            new Action1_N<List<HttpContent>>() {
                @Override
                public void call(final List<HttpContent> currentBlock, final Object...args) {
                    //  retain httpContent by caller
                    final HttpContent content = JOArrays.<HttpContent>takeArgAs(0, args);
                    if (null != content) {
                        currentBlock.add(ReferenceCountUtil.retain(content));
                        _currentBlockSize += content.content().readableBytes();
                    }
                }}));
    
    private final Func0<HttpContent> _funcBuildCurrentBlockAndReset = 
            RxFunctions.toFunc0(
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
        }}));
    
    private final Func1<HttpObject, HttpObject> _funcRetainAndHoldHttpObject = 
            RxFunctions.toFunc1(
            this._reqHttpObjectsRef.callWhenActive(
            new Func1_N<List<HttpObject>, HttpObject>() {
                @Override
                public HttpObject call(final List<HttpObject> reqs,final Object...args) {
                    //  retain httpobj by caller
                    final HttpObject httpobj = JOArrays.<HttpObject>takeArgAs(0, args);
                    if (null != httpobj) {
                        reqs.add(ReferenceCountUtil.retain(httpobj));
                    }
                    return httpobj;
        }}));
    
    private final Func0<FullHttpRequest> _funcRetainFullHttpRequest = 
            RxFunctions.toFunc0(
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
        }}));
}
