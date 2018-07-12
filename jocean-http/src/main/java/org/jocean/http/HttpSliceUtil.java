package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;

import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Single;
import rx.functions.Func1;

public class HttpSliceUtil {
    public static Observable<DisposableWrapper<? extends HttpObject>> elementAndSucceed(final HttpSlice current) {
        return Observable.concat(current.element(),
                current.next().flatMap(new Func1<HttpSlice, Observable<DisposableWrapper<? extends HttpObject>>>() {
                    @Override
                    public Observable<DisposableWrapper<? extends HttpObject>> call(final HttpSlice next) {
                        return elementAndSucceed(next);
                    }
                }));
    }

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

    public static Observable<? extends HttpSlice> single(final Observable<? extends DisposableWrapper<? extends HttpObject>> element) {
        return Observable.just(new HttpSlice() {

            @Override
            public Single<Boolean> hasNext() {
                return Single.just(false);
            }

            @Override
            public Observable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return element;
            }

            @Override
            public Observable<? extends HttpSlice> next() {
                return Observable.empty();
            }});
    }

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
            public Single<Boolean> hasNext() {
                return slice.hasNext();
            }

            @Override
            public Observable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return slice.element().compose(transformer);
            }

            @Override
            public Observable<? extends HttpSlice> next() {
                return slice.next();
            }
        };
    }
}
