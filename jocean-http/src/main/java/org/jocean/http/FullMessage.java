package org.jocean.http;

import io.netty.handler.codec.http.HttpMessage;
import rx.Observable;

public interface FullMessage {
    public <M extends HttpMessage> M message();
    public Observable<? extends MessageBody> body();
}
