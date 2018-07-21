package org.jocean.http;

import rx.Observable;

public interface MessageBody {

    public String contentType();

    public int contentLength();

    public Observable<? extends ByteBufSlice> content();
}
