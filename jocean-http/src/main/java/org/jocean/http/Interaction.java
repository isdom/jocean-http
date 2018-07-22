package org.jocean.http;

import org.jocean.http.client.HttpClient.HttpInitiator;

import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;

public interface Interaction {
    public HttpInitiator  initiator();
    public Observable<? extends FullMessage<HttpResponse>> execute();
}
