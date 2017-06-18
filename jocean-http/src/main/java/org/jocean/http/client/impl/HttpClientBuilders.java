package org.jocean.http.client.impl;

import org.jocean.http.Feature;
import org.jocean.http.client.Outbound;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Feature2Handler;

final class HttpClientBuilders {
    static final Feature2Handler<HttpHandlers> _FOR_INTERACTION;
        
    static final Feature2Handler<HttpHandlers> _FOR_CHANNEL;

    static {
        _FOR_INTERACTION = new Feature2Handler<>();
        _FOR_INTERACTION.register(Feature.ENABLE_LOGGING.getClass(), HttpHandlers.LOGGING);
        _FOR_INTERACTION.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), HttpHandlers.LOGGING_OVER_SSL);
        _FOR_INTERACTION.register(Feature.ENABLE_COMPRESSOR.getClass(), HttpHandlers.CONTENT_DECOMPRESSOR);
        _FOR_INTERACTION.register(Feature.ENABLE_CLOSE_ON_IDLE.class, HttpHandlers.CLOSE_ON_IDLE);
        _FOR_INTERACTION.register(Outbound.ENABLE_MULTIPART.getClass(), HttpHandlers.CHUNKED_WRITER);
        
        _FOR_CHANNEL = new Feature2Handler<>();
        _FOR_CHANNEL.register(Feature.ENABLE_SSL.class, HttpHandlers.SSL);
    }
}
