package org.jocean.http.endpoint;

import org.jocean.http.Interact;
import org.jocean.http.TypedSPI;

import rx.Observable.Transformer;

public interface EndpointSet {
    public String[] uris(final String type);
    public Transformer<Interact, Interact> of(final TypedSPI spi);
}
