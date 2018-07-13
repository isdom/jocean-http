package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.rx.RxIterator;

import io.netty.buffer.ByteBuf;
import rx.Observable;

public interface ByteBufSlice extends RxIterator<Observable<? extends DisposableWrapper<? extends ByteBuf>>> {
    @Override
    public Observable<? extends ByteBufSlice> next();
}
