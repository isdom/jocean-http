package org.jocean.http.endpoint;

import org.jocean.http.Interact;
import org.jocean.http.TypedSPI;

import rx.functions.Func1;

public interface EndpointSet {
    public String[] uris(final String type);
    public Func1<Interact, Interact> of(final TypedSPI spi);
}
