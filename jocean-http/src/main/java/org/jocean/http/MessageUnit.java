package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import rx.Observable;

public interface MessageUnit {
    
    public String contentType();
    
    public int contentLength();
    
    public Observable<? extends DisposableWrapper<ByteBuf>> content();
}
