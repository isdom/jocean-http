package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.rx.RxIterator;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface HttpSlice extends RxIterator<Observable<? extends DisposableWrapper<? extends HttpObject>>> {
    @Override
    public Observable<? extends HttpSlice> next();
}
