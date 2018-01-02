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
            LoggerFactory.getLogger(MessageUtil.class);
    
    private StreamUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Func0<DisposableWrapper<ByteBuf>> allocStateableDWB(final int bufSize) {
        return new Func0<DisposableWrapper<ByteBuf>>() {
            @Override
            public DisposableWrapper<ByteBuf> call() {
                final ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
                final ByteBuf buf = allocator.buffer(8192, 8192);
                return Proxys.mixin().mix(DisposableWrapper.class, RxNettys.wrap4release(buf))
                        .mix(Stateable.class, new StateableSupport()).build();
            }};
    }

    public static <SRC, STATE> Transformer<SRC, DisposableWrapper<ByteBuf>> src2dwb(
            final Func0<DisposableWrapper<ByteBuf>> newdwb, final Func1<SRC, byte[]> src2bytes,
            final Func1<SRC, STATE> newstate, final Action2<SRC, STATE> updatestate) {
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
                            updatestate.call(src, StateableUtil.stateOf(ref.get()));
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
                            return Observable.empty();
                        } else {
                            final DisposableWrapper<ByteBuf> last = ref.getAndSet(null);
                            if (last.unwrap().readableBytes() > 0) {
                                return Observable.just(last);
                            } else {
                                last.dispose();
                                return Observable.empty();
                            }
                        }
                    }
                }).doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        final DisposableWrapper<ByteBuf> last = ref.getAndSet(null);
                        if (null != last) {
                            last.dispose();
                        }
                    }
                });
            }
        };
    }
}
