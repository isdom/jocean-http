package org.jocean.http.rosa.impl.internal;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyPreprocessor;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

class FormURLEncodedBodyPreprocessor implements Feature, BodyPreprocessor {

    @Override
    public BodyBuilder call(final Object signal, final HttpRequest request) {
        if (null != signal
            && request.method().equals(HttpMethod.POST)) {
            return new FormURLEncodedBodyBuilder(signal);
        } else {
            return null;
        }
    }
}
