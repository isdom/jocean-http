package org.jocean.http.rosa.impl.preprocessor;

import org.jocean.http.Feature;
import org.jocean.http.rosa.impl.BodyBuilder;
import org.jocean.http.rosa.impl.BodyPreprocessor;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

class DefaultBodyPreprocessor implements Feature, BodyPreprocessor {

    @Override
    public BodyBuilder call(final Object signal, final HttpRequest request) {
        if (request.getMethod().equals(HttpMethod.POST)) {
            return new JSONBodyBuilder(signal);
        } else {
            return null;
        }
    }
}
