package org.jocean.http.client.impl;

import org.jocean.http.Feature;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Class2ApplyBuilder;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

final class HttpClientConstants {
    final static Feature APPLY_HTTPCLIENT = new Feature.AbstractFeature0() {};

    static final Class2Instance<Feature, ApplyToRequest> _CLS_TO_APPLY2REQ;
    
    static final Class2ApplyBuilder _APPLY_BUILDER_PER_INTERACTION;
        
    static final Class2ApplyBuilder _APPLY_BUILDER_PER_CHANNEL;

    static {
        _APPLY_BUILDER_PER_INTERACTION = new Class2ApplyBuilder();
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), APPLY.LOGGING_OVER_SSL);
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_DECOMPRESSOR);
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER_PER_INTERACTION.register(Outbound.ENABLE_MULTIPART.getClass(), APPLY.CHUNKED_WRITER);
        
        _APPLY_BUILDER_PER_CHANNEL = new Class2ApplyBuilder();
        _APPLY_BUILDER_PER_CHANNEL.register(Feature.ENABLE_SSL.class, APPLY.SSL);
        _APPLY_BUILDER_PER_CHANNEL.register(APPLY_HTTPCLIENT.getClass(), APPLY.HTTPCLIENT);
        
        _CLS_TO_APPLY2REQ = new Class2Instance<>();
        _CLS_TO_APPLY2REQ.register(Feature.ENABLE_COMPRESSOR.getClass(), 
            new ApplyToRequest() {
                @Override
                public void call(final HttpRequest request) {
                    HttpHeaders.addHeader(request,
                            HttpHeaders.Names.ACCEPT_ENCODING, 
                            HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
                }
            });
    }
}
