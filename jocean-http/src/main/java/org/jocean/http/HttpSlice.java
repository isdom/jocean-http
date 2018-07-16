package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Stepable;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface HttpSlice extends Stepable<Observable<? extends DisposableWrapper<? extends HttpObject>>> {
}
