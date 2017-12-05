package org.jocean.http;

import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.Subscription;

public interface MessageDecoder extends Subscription {
    
    public String contentType();
    
    public int contentLength();
    
    public Observable<? extends DisposableWrapper<ByteBuf>> content();
    
    public <T> Observable<? extends T> decodeAs(final Class<T> type);
    
    public <T> Observable<? extends T> decodeJsonAs(final Class<T> type);
    
    public <T> Observable<? extends T> decodeXmlAs(final Class<T> type);
    
    public <T> Observable<? extends T> decodeFormAs(final Class<T> type);
}
