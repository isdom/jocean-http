package org.jocean.http.util;

import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.idiom.rx.RxFunctions;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum APPLY {
    ON_CHANNEL_READ(Functions.fromFunc(FACTORYFUNCS.ON_CHANNEL_READ_FUNC1)),
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    TRAFFICCOUNTER(FACTORYFUNCS.TRAFFICCOUNTER_FUNCN),
    CLOSE_ON_IDLE(Functions.fromFunc(FACTORYFUNCS.CLOSE_ON_IDLE_FUNC1)),
    SSL(Functions.fromFunc(FACTORYFUNCS.SSL_FUNC2)),
    SSLNOTIFY(Functions.fromFunc(FACTORYFUNCS.SSLNOTIFY_FUNC2)),
    LOGGING_OVER_SSL(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    HTTPCLIENT(FACTORYFUNCS.HTTPCLIENT_CODEC_FUNCN),
    HTTPSERVER(FACTORYFUNCS.HTTPSERVER_CODEC_FUNCN),
    CONTENT_DECOMPRESSOR(FACTORYFUNCS.CONTENT_DECOMPRESSOR_FUNCN),
    CONTENT_COMPRESSOR(FACTORYFUNCS.CONTENT_COMPRESSOR_FUNCN),
    CHUNKED_WRITER(FACTORYFUNCS.CHUNKED_WRITER_FUNCN),
    HTTPOBJ_SUBSCRIBER(Functions.fromFunc(FACTORYFUNCS.HTTPOBJ_SUBSCRIBER_FUNC1)),
    ;
    
    public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(APPLY.class);
    
    public ChannelHandler applyTo(final ChannelPipeline pipeline, final Object ... args) {
        if (null==this._factory) {
            throw new UnsupportedOperationException("ChannelHandler's factory is null");
        }
        return Nettys.insertHandler(
            pipeline,
            this.name(), 
            this._factory.call(args), 
            TO_ORDINAL);
    }

    private APPLY(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
}
