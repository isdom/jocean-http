package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Nextable;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface HttpSlice extends Nextable<Observable<? extends DisposableWrapper<? extends HttpObject>>> {
}
