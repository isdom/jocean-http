package org.jocean.http.rosa.impl;

import org.jocean.http.Feature;

import rx.functions.Func0;

class RequestProfile {

    RequestProfile(final Class<?> respCls,
            final String prefix,
            final Func0<Feature[]> builder) {
        this._respCls = respCls;
        this._prefix = prefix;
        this._featuresBuilder = builder;
    }
    
    Class<?> responseType() {
        return this._respCls;
    }
    
    String pathPrefix() {
        return this._prefix;
    }
    
    Feature[] features() {
        return this._featuresBuilder.call();
    }
    
    private final Class<?> _respCls;
    private final String _prefix;
    private final Func0<Feature[]> _featuresBuilder;
}
