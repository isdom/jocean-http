package org.jocean.http.rosa.impl;

import java.net.SocketAddress;
import java.net.URI;

import org.jocean.http.Feature;

import rx.functions.Func0;
import rx.functions.Func1;

class RequestProfile {

    RequestProfile(final Class<?> respType,
            final String uri,
            final Func0<Feature[]> builder,
            final Func1<URI, SocketAddress> uri2address) {
        this._respType = respType;
        this._uri = uri;
        this._featuresBuilder = builder;
        this._uri2address = uri2address;
    }
    
    Class<?> responseType() {
        return this._respType;
    }
    
    String uri() {
        return this._uri;
    }
    
    Feature[] features() {
        return this._featuresBuilder.call();
    }
    
    SocketAddress buildAddress(final URI uri) {
        return this._uri2address.call(uri);
    }
    
    private final Class<?> _respType;
    private final String _uri;
    private final Func0<Feature[]> _featuresBuilder;
    private final Func1<URI, SocketAddress> _uri2address;
}
