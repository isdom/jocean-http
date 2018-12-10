package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Stepable;

import io.netty.buffer.ByteBuf;

public interface ByteBufSlice extends Stepable<Iterable<? extends DisposableWrapper<? extends ByteBuf>>> {
}
