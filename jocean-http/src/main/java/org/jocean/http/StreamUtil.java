package org.jocean.http;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Proxys;
import org.jocean.idiom.Stateable;
import org.jocean.idiom.StateableSupport;
import org.jocean.idiom.StateableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;

public class StreamUtil {
    private static final Logger LOG =
            LoggerFactory.getLogger(StreamUtil.class);
    
    private StreamUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static DisposableWrapper<ByteBuf> allocStateableDWB(final int bufSize) {
        final ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        final ByteBuf buf = allocator.buffer(bufSize, bufSize);
        return Proxys.mixin().mix(DisposableWrapper.class, RxNettys.wrap4release(buf))
                .mix(Stateable.class, new StateableSupport()).build();
    }

    public static <SRC, STATE> Transformer<SRC, DisposableWrapper<ByteBuf>> src2dwb(
            final Func0<DisposableWrapper<ByteBuf>> newdwb, final Func1<SRC, byte[]> src2bytes,
            final Func1<SRC, STATE> newstate, 
            final Action2<SRC, STATE> updatestate) {
        final AtomicReference<DisposableWrapper<ByteBuf>> ref = new AtomicReference<>();

        return new Transformer<SRC, DisposableWrapper<ByteBuf>>() {
            @Override
            public Observable<DisposableWrapper<ByteBuf>> call(final Observable<SRC> upstream) {
                return upstream.flatMap(new Func1<SRC, Observable<DisposableWrapper<ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<ByteBuf>> call(final SRC src) {
                        if (null == ref.get()) {
                            ref.set(newdwb.call());
                            StateableUtil.setStateTo(newstate.call(src), ref.get());
                        }
                        final byte[] bytes = src2bytes.call(src);
                        if (bytes.length <= ref.get().unwrap().maxWritableBytes()) {
                            ref.get().unwrap().writeBytes(bytes);
                            updatestate.call(src, StateableUtil.<STATE>stateOf(ref.get()));
                            return Observable.empty();
                        } else {
                            final DisposableWrapper<ByteBuf> newbuf = newdwb.call();
                            newbuf.unwrap().writeBytes(bytes);
                            StateableUtil.setStateTo(newstate.call(src), newbuf);
                            return Observable.just(ref.getAndSet(newbuf));
                        }
                    }
                }, new Func1<Throwable, Observable<DisposableWrapper<ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<ByteBuf>> call(final Throwable e) {
                        return Observable.error(e);
                    }
                }, new Func0<Observable<DisposableWrapper<ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<ByteBuf>> call() {
                        if (null == ref.get()) {
                            LOG.debug("src2dwb onCompleted with ref is null");
                            return Observable.empty();
                        } else {
                            final DisposableWrapper<ByteBuf> last = ref.getAndSet(null);
                            if (last.unwrap().readableBytes() > 0) {
                                LOG.debug("src2dwb onCompleted with last as content");
                                return Observable.just(last);
                            } else {
                                last.dispose();
                                LOG.debug("src2dwb onCompleted with last NO content");
                                return Observable.empty();
                            }
                        }
                    }
                }).doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        final DisposableWrapper<ByteBuf> last = ref.getAndSet(null);
                        if (null != last) {
                            LOG.debug("src2dwb doOnUnsubscribe with last disposed.");
                            last.dispose();
                        }
                    }
                });
            }
        };
    }
    
    static class _EndEvent extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    
    public static <STATE> Observable<? extends DisposableWrapper<ByteBuf>> buildContent(
            final Observable<Object> sended,
            final Func1<STATE, Observable<DisposableWrapper<ByteBuf>>> state2dwbs, 
            final Func1<STATE, Boolean> isend) {
        
        final Observable<? extends DisposableWrapper<ByteBuf>> cachedContent = 
                sended.compose(sended2content(state2dwbs, isend))
                .onErrorResumeNext(new Func1<Throwable, Observable<DisposableWrapper<ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<ByteBuf>> call(final Throwable e) {
                        if (e instanceof _EndEvent) {
                            return Observable.empty();
                        } else {
                            return Observable.error(e);
                        }
                    }})
                .cache();
        cachedContent.subscribe();
        return cachedContent;
    }
    
    private static <STATE> Transformer<Object, DisposableWrapper<ByteBuf>> sended2content(
            final Func1<STATE, Observable<DisposableWrapper<ByteBuf>>> state2dwbs, 
            final Func1<STATE, Boolean> isend) {
        return new Transformer<Object, DisposableWrapper<ByteBuf>>() {
            @Override
            public Observable<DisposableWrapper<ByteBuf>> call(final Observable<Object> sended) {
                return sended.flatMap(new Func1<Object, Observable<DisposableWrapper<ByteBuf>>>() {
                    @Override
                    public Observable<DisposableWrapper<ByteBuf>> call(final Object obj) {
                        final STATE state = StateableUtil.stateOf(obj);
                        
                        final Observable<DisposableWrapper<ByteBuf>> dwbs = state2dwbs.call(state);
                        if (null != dwbs) {
                            LOG.debug("sended2content: null != dwbs and return dwbs");
                            return dwbs;
                        } else if (isend.call(state)) {
                            LOG.debug("sended2content: on the end, and push end event");
                            return Observable.error(new _EndEvent());
                        }
                        LOG.debug("sended2content: return Observable.empty()");
                        return Observable.empty();
                    }} );
            }};
    }
}
