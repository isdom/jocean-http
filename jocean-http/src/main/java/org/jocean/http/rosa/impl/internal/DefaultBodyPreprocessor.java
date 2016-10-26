package org.jocean.http.rosa.impl.internal;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyPreprocessor;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

class DefaultBodyPreprocessor implements Feature, BodyPreprocessor {

    @Override
    public BodyBuilder call(final Object signal, final HttpRequest request) {
        final RAWBodyBuilder rawBuilder = RAWBodyBuilder.hasRAWBody(signal);
        if (null != rawBuilder) {
            return rawBuilder;
        }
        if (null != signal
            && request.method().equals(HttpMethod.POST)) {
            return new JSONBodyBuilder(signal);
        } else {
            return null;
        }
    }
}
