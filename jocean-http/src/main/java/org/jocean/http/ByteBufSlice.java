package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Stepable;

import io.netty.buffer.ByteBuf;
import rx.Observable;

public interface ByteBufSlice extends Stepable<Observable<? extends DisposableWrapper<? extends ByteBuf>>> {
}
