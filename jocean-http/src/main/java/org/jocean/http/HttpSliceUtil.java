package org.jocean.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Func1;

public class HttpSliceUtil {
    /*
    public static <T extends HttpMessage> Transformer<HttpSlice, T> extractHttpMessage() {
        return new Transformer<HttpSlice, T>() {
            @Override
            public Observable<T> call(final Observable<HttpSlice> slices) {
                return slices.flatMap(new Func1<HttpSlice, Observable<? extends DisposableWrapper<? extends HttpObject>>>() {
                            @Override
                            public Observable<? extends DisposableWrapper<? extends HttpObject>> call(final HttpSlice slice) {
                                return slice.element();
                            }
                        }).map(DisposableWrapperUtil.<HttpObject>unwrap()).first().map(new Func1<HttpObject, T>() {
                            @SuppressWarnings("unchecked")
                            @Override
                            public T call(final HttpObject httpobj) {
                                if (httpobj instanceof HttpMessage) {
                                    return (T) httpobj;
                                } else {
                                    throw new RuntimeException("First HttpObject is not HttpMessage.");
                                }
                            }
                        });
            }
        };
    }
    */

    public static Observable<HttpSlice> single(final Iterable<? extends DisposableWrapper<? extends HttpObject>> element) {
        return Observable.<HttpSlice>just(new HttpSlice() {
            @Override
            public Iterable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return element;
            }
            @Override
            public void step() {}});
    }

    /*
    public static Func1<HttpSlice, HttpSlice> transformElement(
            final Transformer<DisposableWrapper<? extends HttpObject>, DisposableWrapper<? extends HttpObject>> transformer) {
        return new Func1<HttpSlice, HttpSlice>() {
            @Override
            public HttpSlice call(final HttpSlice slice) {
                return transformElement(slice, transformer);
            }
        };
    }

    public static HttpSlice transformElement(final HttpSlice slice,
            final Transformer<DisposableWrapper<? extends HttpObject>, DisposableWrapper<? extends HttpObject>> transformer) {
        return new HttpSlice() {
            @Override
            public Iterable<DisposableWrapper<? extends HttpObject>> element() {
                return slice.element().compose(transformer);
            }
            @Override
            public void step() {
                slice.step();
            }
        };
    }
    */

    private final static Func1<HttpSlice, ByteBufSlice> _HS2BBS = new Func1<HttpSlice, ByteBufSlice>() {
        @Override
        public ByteBufSlice call(final HttpSlice slice) {
            final List<DisposableWrapper<ByteBuf>> dwbs = new ArrayList<>();
            for (final Iterator<? extends DisposableWrapper<? extends HttpObject>> iter = slice.element().iterator(); iter.hasNext(); ) {
                final DisposableWrapper<ByteBuf> dwb = RxNettys.dwc2dwb(iter.next());
                if (null != dwb) {
                    dwbs.add(dwb);
                }
            }

            return new ByteBufSlice() {
                @Override
                public String toString() {
                    return new StringBuilder().append("ByteBufSlice [from ").append(slice).append("]").toString();
                }
                @Override
                public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                    return dwbs;
                }
                @Override
                public void step() {
                    slice.step();
                }};
        }
    };

    public static Func1<HttpSlice, ByteBufSlice> hs2bbs() {
        return _HS2BBS;
    }
}
