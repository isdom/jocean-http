package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Stepable;

import io.netty.handler.codec.http.HttpObject;

public interface HttpSlice extends Stepable<Iterable<? extends DisposableWrapper<? extends HttpObject>>> {
}
