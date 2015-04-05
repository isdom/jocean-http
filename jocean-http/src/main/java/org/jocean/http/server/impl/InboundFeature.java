package org.jocean.http.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.logging.LoggingHandler;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.RxFunctions;

import rx.functions.FuncN;
import rx.functions.Functions;

public enum InboundFeature {
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC2)),
    ENABLE_SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
    HTTPSERVER_CODEC(null),
    CONTENT_COMPRESSOR(Nettys.CONTENT_COMPRESSOR_FUNCN),
    LAST_FEATURE(null)
    ;
    
    public Channel applyTo(final Channel channel, final Object ... args) {
        if (null==this._factory) {
            throw new UnsupportedOperationException("ChannelHandler's factory is null");
        }
        Nettys.insertHandler(
            channel.pipeline(),
            this.name(), 
            this._factory.call(JOArrays.addFirst(args, channel, Object[].class)), 
            TO_ORDINAL);
        return channel;
    }

    public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(InboundFeature.class);
    
    private InboundFeature(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
}
