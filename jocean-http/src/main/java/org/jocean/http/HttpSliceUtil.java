package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;
import rx.functions.Func1;

public class HttpSliceUtil {
    public static Observable<DisposableWrapper<? extends HttpObject>> slice2dwhs(final HttpSlice current) {
        return Observable.concat(current.element(),
                current.next().flatMap(new Func1<HttpSlice, Observable<DisposableWrapper<? extends HttpObject>>>() {
                    @Override
                    public Observable<DisposableWrapper<? extends HttpObject>> call(final HttpSlice next) {
                        return slice2dwhs(next);
                    }
                }));
    }
}
