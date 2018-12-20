package org.jocean.http;

import org.jocean.http.client.HttpClient;

import rx.Observable;

public interface InteractBuilder {

    public Observable<Interact> interact(final HttpClient client);
}
