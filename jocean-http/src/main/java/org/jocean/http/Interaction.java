package org.jocean.http;

import org.jocean.http.client.HttpClient.HttpInitiator;

import rx.Observable;

public interface Interaction {
    public HttpInitiator  initiator();
    public Observable<? extends HttpSlice> execute();
}
