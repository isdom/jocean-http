package org.jocean.netty.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.RxObservables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.ActionN;
import rx.functions.Func0;
import rx.functions.Func1;

public class ReferenceCountedHolder {
    private static final ReferenceCounted[] __ZERO_REFCNTED = new ReferenceCounted[0];

    private static final Logger LOG = LoggerFactory
            .getLogger(ReferenceCountedHolder.class);
    
    @SuppressWarnings("unchecked")
    public <R> Func0<R> fullBuilder(final Func1<ReferenceCounted[], R> visitor) {
        return new Func0<R>() {
            @Override
            public R call() {
                return (R)_op.visitReferenceCounteds(
                        ReferenceCountedHolder.this, 
                        (Func1<ReferenceCounted[], Object>) visitor);
            }};
    }
    
    public void releaseReferenceCounted(final ReferenceCounted obj) {
        this._op.releaseReferenceCounted(this, obj);
    }
    
    public int cachedSize() {
        return this._op.cachedSize(this);
    }
    
    public boolean isFragmented() {
        return this._fragmented.get();
    }
    
    private final static ActionN DO_RELEASE = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((ReferenceCountedHolder)args[0]).doRelease();
        }};
        
    public Action0 release() {
        return new Action0() {
        @Override
        public void call() {
            _selector.destroyAndSubmit(DO_RELEASE, ReferenceCountedHolder.this);
        }};
    }
    
    private void doRelease() {
        releaseReferenceCountedQueue(this._cachedReferenceCounteds);
    }

    private static <T> void releaseReferenceCountedQueue(final Queue<T> objs) {
        for (;;) {
            final T obj = objs.poll();
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
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    private final Op _op = _selector.build(Op.class, 
            OP_WHEN_ACTIVE, 
            OP_WHEN_UNACTIVE);
    
    protected interface Op {
        public Object visitReferenceCounteds(final ReferenceCountedHolder holder, 
                final Func1<ReferenceCounted[], Object> visitor);
        public void releaseReferenceCounted(final ReferenceCountedHolder holder, 
                final ReferenceCounted content);
        public int cachedSize(final ReferenceCountedHolder holder); 
        public ReferenceCounted retainAndHoldReferenceCounted(final ReferenceCountedHolder holder, 
                final ReferenceCounted referenceCounted);
    }
    
    private static final Op OP_WHEN_ACTIVE = new Op() {
        @Override
        public Object visitReferenceCounteds(final ReferenceCountedHolder holder,
                final Func1<ReferenceCounted[], Object> visitor) {
            if (holder._fragmented.get()) {
                //  some refobj has been released
                //  TODO, need throw Exception
                return null;
            }
            try {
                return visitor.call(holder._cachedReferenceCounteds.toArray(__ZERO_REFCNTED));
            } catch (Exception e) {
                LOG.warn("exception when invoke visitor({}), detail: {}",
                        visitor, ExceptionUtils.exception2detail(e));
                return null;
            }
        }

        @Override
        public void releaseReferenceCounted(final ReferenceCountedHolder holder,
                final ReferenceCounted referenceCounted) {
            holder.releaseCached0(referenceCounted);
        }

        @Override
        public int cachedSize(final ReferenceCountedHolder holder) {
            return holder._cachedReferenceCounteds.size();
        }

        @Override
        public ReferenceCounted retainAndHoldReferenceCounted(
                final ReferenceCountedHolder holder,
                final ReferenceCounted referenceCounted) {
            if (null != referenceCounted) {
                holder._cachedReferenceCounteds.add(ReferenceCountUtil.retain(referenceCounted));
            }
            return referenceCounted;
        }

    };
    
    private static final Op OP_WHEN_UNACTIVE = new Op() {
        @Override
        public Object visitReferenceCounteds(final ReferenceCountedHolder holder,
                final Func1<ReferenceCounted[], Object> visitor) {
            return null;
        }

        @Override
        public void releaseReferenceCounted(ReferenceCountedHolder holder,
                ReferenceCounted content) {
        }

        @Override
        public int cachedSize(ReferenceCountedHolder holder) {
            return 0;
        }

        @Override
        public ReferenceCounted retainAndHoldReferenceCounted(
                ReferenceCountedHolder holder,
                ReferenceCounted referenceCounted) {
            return null;
        }
    };
    
    private void releaseCached0(final ReferenceCounted obj) {
        synchronized(this) {
            final Iterator<ReferenceCounted> iter = 
                    this._cachedReferenceCounteds.iterator();
            while (iter.hasNext()) {
                final ReferenceCounted cached = iter.next();
                if (cached == obj) {
                    LOG.info("found ReferenceCounted {} to release ", obj);
                    this._fragmented.compareAndSet(false, true);
                    iter.remove();
                    ReferenceCountUtil.release(cached);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ReferenceCounted {} has been removed from holder({}) and released",
                                cached, this);
                    }
                    return;
                }
            }
            LOG.info("COULD NOT found ReferenceCounted {} to release ", obj);
        }
    }
    
    private final Func1<ReferenceCounted, Observable<? extends ReferenceCounted>> _HOLD_REFCNTED = 
    new Func1<ReferenceCounted, Observable<? extends ReferenceCounted>>() {
        @Override
        public Observable<? extends ReferenceCounted> call(final ReferenceCounted obj) {
            return asObservable(_op.retainAndHoldReferenceCounted(ReferenceCountedHolder.this, obj));
        }};
        
    private final Transformer<ReferenceCounted, ReferenceCounted> _TRANS_OF_HOLD = 
        new Transformer<ReferenceCounted, ReferenceCounted>() {
            @Override
            public Observable<ReferenceCounted> call(final Observable<ReferenceCounted> source) {
                return source.flatMap(_HOLD_REFCNTED)
                    .compose(RxObservables.<ReferenceCounted>ensureSubscribeAtmostOnce());
            }};
            
    @SuppressWarnings("unchecked")
    public <T> Transformer<T, T> holding() {
        return (Transformer<T, T>)_TRANS_OF_HOLD;
    }

    @SuppressWarnings("unchecked")
    public <T extends ReferenceCounted> T hold(final T referenceCounted) {
        return (T)this._op.retainAndHoldReferenceCounted(this, referenceCounted);
    }
    
    private static Observable<ReferenceCounted> asObservable(final ReferenceCounted... objs) {
        final List<ReferenceCounted> src = new ArrayList<>();
        for (ReferenceCounted obj : objs) {
            if (null != obj) {
                src.add(obj);
            }
        }
        return (!src.isEmpty()) ? Observable.from(src) : Observable.<ReferenceCounted>empty();
    }

    private final AtomicBoolean _fragmented = new AtomicBoolean(false);
    
    private final Queue<ReferenceCounted> _cachedReferenceCounteds = new ConcurrentLinkedQueue<>();
}
