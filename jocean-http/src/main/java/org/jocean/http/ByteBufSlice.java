package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Nextable;

import io.netty.buffer.ByteBuf;
import rx.Observable;

public interface ByteBufSlice extends Nextable<Observable<? extends DisposableWrapper<? extends ByteBuf>>> {
}
