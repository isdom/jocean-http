package org.jocean.http.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.RxObservables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.ActionN;
import rx.functions.Func0;
import rx.functions.Func1;

public class HttpMessageHolder {
    private static final HttpObject[] __ZERO_HTTPOBJS = new HttpObject[0];

    private static int _block_size = 128 * 1024; // 128KB
    static {
        // possible system property for overriding
        final String sizeFromProperty = System.getProperty("org.jocean.http.msgholder.blocksize");
        if (sizeFromProperty != null) {
            try {
                _block_size = Integer.parseInt(sizeFromProperty);
            } catch (Exception e) {
                System.err.println("Failed to set 'org.jocean.http.msgholder.blocksize' with value " 
                        + sizeFromProperty + " => " + e.getMessage());
            }
        }
    }
    
    private final static int _MAX_BLOCK_SIZE = _block_size;
    
    private static final Logger LOG = LoggerFactory
            .getLogger(HttpMessageHolder.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    private final Op _op = _selector.build(Op.class, 
            OP_WHEN_ACTIVE, 
            OP_WHEN_UNACTIVE);
    
    public HttpMessageHolder(final int maxBlockSize) {
        //  0 : using _MAX_BLOCK_SIZE 
        //  -1 : disable assemble piece to a big block feature
        this._enableAssemble = maxBlockSize >= 0;
        this._maxBlockSize = maxBlockSize > 0 ? maxBlockSize : _MAX_BLOCK_SIZE;
    }
    
    public <R> Func0<R> httpMessageBuilder(final Func1<HttpObject[], R> visitor) {
        return new Func0<R>() {
            @SuppressWarnings("unchecked")
            @Override
            public R call() {
                return (R)_op.visitHttpObjects(HttpMessageHolder.this, 
                        (Func1<HttpObject[], Object>)visitor);
            }};
    }
    
    public boolean isFragmented() {
        return this._fragmented.get();
    }
    
    protected interface Op {
        public Object visitHttpObjects(final HttpMessageHolder holder, 
                final Func1<HttpObject[], Object> visitor);
        public void releaseHttpContent(final HttpMessageHolder holder, 
                final HttpContent content);
        public int currentBlockCount(final HttpMessageHolder holder); 
        public int cachedHttpObjectCount(final HttpMessageHolder holder);
        public void retainAndUpdateCurrentBlock(final HttpMessageHolder holder, 
                final HttpContent content);
        public HttpObject retainAndHoldHttpObject(final HttpMessageHolder holder, 
                final HttpObject httpobj);
        public HttpContent buildCurrentBlockAndReset(final HttpMessageHolder holder);
    }
    
    private static final Op OP_WHEN_ACTIVE = new Op() {
        @Override
        public Object visitHttpObjects(final HttpMessageHolder holder, final Func1<HttpObject[], Object> visitor) {
            if (holder._fragmented.get()) {
                //  some httpobj has been released
                //  TODO, need throw Exception
                return null;
            }
            try {
                return visitor.call(holder._cachedHttpObjects.toArray(__ZERO_HTTPOBJS));
            } catch (Exception e) {
                LOG.warn("exception when invoke visitor({}), detail: {}",
                        visitor, ExceptionUtils.exception2detail(e));
                return null;
            }
        }
        
        @Override
        public void releaseHttpContent(final HttpMessageHolder holder,
                final HttpContent content) {
            synchronized(holder) {
                final Iterator<HttpObject> iter = holder._cachedHttpObjects.iterator();
                while (iter.hasNext()) {
                    final HttpObject obj = iter.next();
                    if (obj instanceof HttpContent) {
                        if ( Nettys.isSameByteBuf(((HttpContent)obj).content(), content.content()) ) {
                            LOG.info("found HttpContent {} to release ", obj);
                            holder._fragmented.compareAndSet(false, true);
                            for (;;) {
                                final HttpObject removedObj = holder._cachedHttpObjects.poll();
                                final boolean stop = 
                                    Nettys.isSameByteBuf(((HttpContent)removedObj).content(), content.content());
                                holder.reduceRetainedSize(removedObj);
                                ReferenceCountUtil.release(removedObj);
                                if (LOG.isDebugEnabled()) {
                                    LOG.info("httpobj {} has been removed from holder({}) and released",
                                        removedObj, holder);
                                }
                                if (stop) {
                                    break;
                                }
                            }
                            return;
                        }
                    }
                }
                LOG.info("COULD NOT found HttpContent {} to release ", content);
            }
        }

        @Override
        public int currentBlockCount(final HttpMessageHolder holder) {
            return holder._currentBlock.size();
        }

        @Override
        public int cachedHttpObjectCount(final HttpMessageHolder holder) {
            return holder._cachedHttpObjects.size();
        }

        @Override
        public void retainAndUpdateCurrentBlock(final HttpMessageHolder holder,
                final HttpContent content) {
            if (null != content) {
                holder._currentBlock.add(ReferenceCountUtil.retain(content));
                holder._currentBlockSize += content.content().readableBytes();
            }
        }

        @Override
        public HttpObject retainAndHoldHttpObject(final HttpMessageHolder holder,
                final HttpObject httpobj) {
            if (null != httpobj) {
                holder._cachedHttpObjects.add(ReferenceCountUtil.retain(httpobj));
                holder.addRetainedSize(httpobj);
            }
            return httpobj;
        }

        @Override
        public HttpContent buildCurrentBlockAndReset(final HttpMessageHolder holder) {
            try {
                if (holder._currentBlock.size()>1) {
                    final ByteBuf[] bufs = new ByteBuf[holder._currentBlock.size()];
                    for (int idx = 0; idx<holder._currentBlock.size(); idx++) {
                        bufs[idx] = holder._currentBlock.get(idx).content();
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("build block: assemble {} HttpContent to composite content with size {} KB",
                                bufs.length, (float)holder._currentBlockSize / 1024f);
                    }
                    return new DefaultHttpContent(Unpooled.wrappedBuffer(bufs.length, bufs));
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("build block: only one HttpContent with {} KB to build block, so pass through",
                                (float)holder._currentBlockSize / 1024f);
                    }
                    return holder._currentBlock.get(0);
                }
            } finally {
                holder._currentBlock.clear();
                holder._currentBlockSize = 0;
            }
        }};
    
    private static final Op OP_WHEN_UNACTIVE = new Op() {
        @Override
        public Object visitHttpObjects(final HttpMessageHolder holder, 
                final Func1<HttpObject[], Object> visitor) {
            return null;
        }
        
        @Override
        public void releaseHttpContent(HttpMessageHolder holder,
                HttpContent content) {
        }

        @Override
        public int currentBlockCount(HttpMessageHolder holder) {
            return 0;
        }

        @Override
        public int cachedHttpObjectCount(HttpMessageHolder holder) {
            return 0;
        }

        @Override
        public void retainAndUpdateCurrentBlock(HttpMessageHolder holder,
                HttpContent content) {
        }

        @Override
        public HttpObject retainAndHoldHttpObject(HttpMessageHolder holder,
                HttpObject httpobj) {
            return null;
        }

        @Override
        public HttpContent buildCurrentBlockAndReset(HttpMessageHolder holder) {
            return null;
        }
    };
    
    private final static ActionN DO_RELEASE = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((HttpMessageHolder)args[0]).doRelease();
        }};
        
    public Action0 release() {
        return new Action0() {
        @Override
        public void call() {
            _selector.destroyAndSubmit(DO_RELEASE, HttpMessageHolder.this);
        }};
    }
    
    private void doRelease() {
        releaseReferenceCountedList(this._currentBlock);
        releaseReferenceCountedQueue(this._cachedHttpObjects);
        this._currentBlockSize = 0;
        this._retainedByteBufSize.set(0);
    }

    private static <T> void releaseReferenceCountedList(final List<T> objs) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("release {} contain {} element.", objs, objs.size());
        }
        for (T obj : objs) {
            final boolean released = ReferenceCountUtil.release(obj);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Obj({}) released({}) from releaseReferenceCountedList", 
                        obj, released);
            }
        }
        objs.clear();
    }
    
    private static <T> void releaseReferenceCountedQueue(final Queue<T> objs) {
        for (;;) {
            T obj = objs.poll();
            if (null == obj) {
                break;
            }
            final boolean released = ReferenceCountUtil.release(obj);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Obj({}) released({}) from releaseReferenceCountedQueue", 
                        obj, released);
            }
        }
    }
    
    public void releaseHttpContent(final HttpContent content) {
        this._op.releaseHttpContent(this, content);
    }
    
    public int currentBlockSize() {
        return this._currentBlockSize;
    }
    
    public int currentBlockCount() {
        return this._op.currentBlockCount(this);
    }
    
    public int cachedHttpObjectCount() {
        return this._op.cachedHttpObjectCount(this);
    }
    
    private final Func1<Object, Observable<? extends Object>> _ASSEMBLE_AND_HOLD = 
    new Func1<Object, Observable<? extends Object>>() {
        @Override
        public Observable<? extends Object> call(final Object obj) {
            if (obj instanceof HttpObject) {
                final HttpObject msg = (HttpObject)obj;
                if (LOG.isDebugEnabled()) {
                    if (msg instanceof ByteBufHolder) {
                        LOG.debug("HttpMessageHolder: receive ByteBufHolder's content: {}", 
                                Nettys.dumpByteBufHolder((ByteBufHolder)msg));
                    } else {
                        LOG.debug("HttpMessageHolder: receive HttpObject: {}", msg);
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
            } else {
                return Observable.just(obj);
            }
        }};
        
    private final Transformer<Object, Object> _TRANS_OF_ASSEMBLE_AND_HOLD = 
        new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> source) {
                return source.flatMap(_ASSEMBLE_AND_HOLD)
                    .compose(RxObservables.ensureSubscribeAtmostOnce());
            }};
            
    @SuppressWarnings("unchecked")
    public <T> Transformer<T, T> assembleAndHold() {
        return (Transformer<T, T>)_TRANS_OF_ASSEMBLE_AND_HOLD;
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
        this._op.retainAndUpdateCurrentBlock(this, content);
    }

    private HttpObject retainAndHoldHttpObject(final HttpObject httpobj) {
        return this._op.retainAndHoldHttpObject(this, httpobj);
    }
    
    private void addRetainedSize(final HttpObject httpobj) {
        if (httpobj instanceof ByteBufHolder) {
            this._retainedByteBufSize.addAndGet(
                ((ByteBufHolder)httpobj).content().readableBytes());
        }
    }
    
    private void reduceRetainedSize(final HttpObject httpobj) {
        if (httpobj instanceof ByteBufHolder) {
            this._retainedByteBufSize.addAndGet(
                -((ByteBufHolder)httpobj).content().readableBytes());
        }
    }
    
    private HttpObject retainCurrentBlockAndReset() {
        final HttpContent content = this._op.buildCurrentBlockAndReset(this);
        try {
            return retainAndHoldHttpObject(content);
        } finally {
            if (null!=content) {
                ReferenceCountUtil.release(content);
            }
        }
    }

    public int retainedByteBufSize() {
        return this._retainedByteBufSize.get();
    }
    
    private final AtomicInteger _retainedByteBufSize = new AtomicInteger(0);
    
    //  if any httpobj has release from holder when it active, then fragmented is true
    private final AtomicBoolean _fragmented = new AtomicBoolean(false);
    
    private final boolean _enableAssemble;
    
    private final int _maxBlockSize;
    
    private final List<HttpContent> _currentBlock = new ArrayList<>();
    
    private volatile int _currentBlockSize = 0;

    private final Queue<HttpObject> _cachedHttpObjects = new ConcurrentLinkedQueue<>();
}
