package org.jocean.http;

import io.netty.handler.codec.http.HttpHeaders;
import rx.Observable;

public interface MessageBody {

    public HttpHeaders headers();

    public String contentType();

    public int contentLength();

    public Observable<? extends ByteBufSlice> content();
}
