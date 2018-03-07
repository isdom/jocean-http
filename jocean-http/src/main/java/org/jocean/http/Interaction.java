package org.jocean.http;

import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.idiom.DisposableWrapper;

import io.netty.handler.codec.http.HttpObject;
import rx.Observable;

public interface Interaction {
    public HttpInitiator  initiator();
    public Observable<? extends DisposableWrapper<HttpObject>> execute();
}
