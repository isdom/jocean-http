package org.jocean.http.rosa.impl.internal;

import java.util.Arrays;
import java.util.Collection;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

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
            final Produces produces = signal.getClass().getAnnotation(Produces.class);
            if (null != produces) {
                final Collection<String> mimeTypes = Arrays.asList(produces.value());
                if (mimeTypes.contains(MediaType.APPLICATION_XML)) {
                    return new XMLBodyBuilder(signal);
                }
            }
            return new JSONBodyBuilder(signal);
        } else {
            return null;
        }
    }
}
