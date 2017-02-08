package org.jocean.netty.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
import org.jocean.idiom.rx.RxObservables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ReferenceCountedHolder {
    private static final ReferenceCounted[] __ZERO_REFCNTED = new ReferenceCounted[0];

    private static final Logger LOG = LoggerFactory
            .getLogger(ReferenceCountedHolder.class);
    
    private final FuncSelector<ReferenceCountedHolder> _selector = 
            new FuncSelector<>(this);
    
    @SuppressWarnings("unchecked")
    public <R> Func0<R> fullBuilder(final Func1<ReferenceCounted[], R> visitor) {
        return new Func0<R>() {
            @Override
            public R call() {
                return (R)_funcVisitReferenceCounteds.call((Func1<ReferenceCounted[], Object>) visitor);
            }};
    }
    
    public boolean isFragmented() {
        return this._fragmented.get();
    }
    
    private final Func1<Func1<ReferenceCounted[], Object>,Object> _funcVisitReferenceCounteds = 
        RxFunctions.toFunc1(
            this._selector.callWhenActive(
                RxFunctions.<ReferenceCountedHolder, Object>toFunc1_N(
                    ReferenceCountedHolder.class, "doVisitReferenceCounteds"))
            .callWhenDestroyed(new Func1_N<ReferenceCountedHolder, Object>() {
                @Override
                public Object call(final ReferenceCountedHolder holder, final Object... args) {
                    return null;
                }}));
    
    @SuppressWarnings("unused")
    private Object doVisitReferenceCounteds(final Func1<ReferenceCounted[], Object> visitor) {
        if (this._fragmented.get()) {
            //  some httpobj has been released
            //  TODO, need throw Exception
            return null;
        }
        try {
            return visitor.call(this._cachedReferenceCounteds.toArray(__ZERO_REFCNTED));
        } catch (Exception e) {
            LOG.warn("exception when invoke visitor({}), detail: {}",
                    visitor, ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    public Action0 release() {
        return new Action0() {
        @Override
        public void call() {
            _selector.destroy(RxActions.toAction1_N(ReferenceCountedHolder.class, "doRelease"));
        }};
    }
    
    @SuppressWarnings("unused")
    private void doRelease() {
        releaseReferenceCountedList(this._cachedReferenceCounteds);
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
    
    public void releaseUntil(final ReferenceCounted included) {
        this._actionReleaseCached.call(included);
    }
    
    private final Action1<ReferenceCounted> _actionReleaseCached = 
            RxActions.toAction1(
                this._selector.submitWhenActive(
                    RxActions.toAction1_N(ReferenceCountedHolder.class, "doReleaseCached")));
    
    @SuppressWarnings("unused")
    private void doReleaseCached(final ReferenceCounted obj) {
        int idx = 0;
        while (idx < this._cachedReferenceCounteds.size()) {
            final ReferenceCounted cached = this._cachedReferenceCounteds.get(idx);
            if ( cached == obj ) {
                LOG.info("found ReferenceCounted {} to release ", obj);
                this._fragmented.compareAndSet(false, true);
                for (int i = 0; i<=idx; i++) {
                    final ReferenceCounted removedObj = this._cachedReferenceCounteds.remove(0);
                    ReferenceCountUtil.release(removedObj);
                    if (LOG.isDebugEnabled()) {
                        LOG.info("ReferenceCounted {} has been removed from holder({}) and released",
                            removedObj, this);
                    }
                }
                break;
            }
            idx++;
        }
    }
    
    public int cachedSize() {
        return this._selector.callWhenActive(new Func1_N<ReferenceCountedHolder, Integer>() {
            @Override
            public Integer call(final ReferenceCountedHolder holder, final Object... args) {
                return holder._cachedReferenceCounteds.size();
            }}).callWhenDestroyed(new Func1_N<ReferenceCountedHolder, Integer>() {
            @Override
            public Integer call(final ReferenceCountedHolder holder, final Object... args) {
                return 0;
            }}).call();
    }
    
    private final Func1<ReferenceCounted, Observable<? extends ReferenceCounted>> _HOLD_REFCNTED = 
    new Func1<ReferenceCounted, Observable<? extends ReferenceCounted>>() {
        @Override
        public Observable<? extends ReferenceCounted> call(final ReferenceCounted obj) {
            return asObservable(retainAndHoldReferenceCounted(obj));
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
        return (T)retainAndHoldReferenceCounted(referenceCounted);
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

    private ReferenceCounted retainAndHoldReferenceCounted(final ReferenceCounted obj) {
        return this._funcRetainAndHoldReferenceCounted.call(obj);
    }
    
    private final Func1<ReferenceCounted, ReferenceCounted> _funcRetainAndHoldReferenceCounted = 
        RxFunctions.toFunc1(
            this._selector.callWhenActive(
                RxFunctions.<ReferenceCountedHolder, ReferenceCounted>toFunc1_N(
                        ReferenceCountedHolder.class, "doRetainAndHoldReferenceCounted")));
    
    @SuppressWarnings("unused")
    private ReferenceCounted doRetainAndHoldReferenceCounted(final ReferenceCounted referenceCounted) {
        if (null != referenceCounted) {
            this._cachedReferenceCounteds.add(ReferenceCountUtil.retain(referenceCounted));
        }
        return referenceCounted;
    }

    private final AtomicBoolean _fragmented = new AtomicBoolean(false);
    
    private final List<ReferenceCounted> _cachedReferenceCounteds = new ArrayList<>();
}
