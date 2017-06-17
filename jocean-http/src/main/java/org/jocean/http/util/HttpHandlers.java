package org.jocean.http.util;

import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.idiom.rx.RxFunctions;

import io.netty.channel.ChannelHandler;
import io.netty.handler.logging.LoggingHandler;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum HttpHandlers implements HandlerType {
    ON_CHANNEL_READ(Functions.fromFunc(HttpFuncs.ON_CHANNEL_READ_FUNC1)),
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    TRAFFICCOUNTER(HttpFuncs.TRAFFICCOUNTER_FUNCN),
    CLOSE_ON_IDLE(Functions.fromFunc(HttpFuncs.CLOSE_ON_IDLE_FUNC1)),
    SSL(Functions.fromFunc(HttpFuncs.SSL_FUNC2)),
    SSLNOTIFY(Functions.fromFunc(HttpFuncs.SSLNOTIFY_FUNC2)),
    LOGGING_OVER_SSL(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    HTTPCLIENT(HttpFuncs.HTTPCLIENT_CODEC_FUNCN),
    HTTPSERVER(HttpFuncs.HTTPSERVER_CODEC_FUNCN),
    CONTENT_DECOMPRESSOR(HttpFuncs.CONTENT_DECOMPRESSOR_FUNCN),
    CONTENT_COMPRESSOR(HttpFuncs.CONTENT_COMPRESSOR_FUNCN),
    CHUNKED_WRITER(HttpFuncs.CHUNKED_WRITER_FUNCN),
    HTTPOBJ_SUBSCRIBER(Functions.fromFunc(HttpFuncs.HTTPOBJ_SUBSCRIBER_FUNC1)),
    
    ON_MESSAGE(Functions.fromFunc(CommonFuncs.ON_MESSAGE_FUNC1)),
    ON_EXCEPTION_CAUGHT(Functions.fromFunc(CommonFuncs.ON_EXCEPTION_CAUGHT_FUNC1)),
    ON_CHANNEL_INACTIVE(Functions.fromFunc(CommonFuncs.ON_CHANNEL_INACTIVE_FUNC1)),
    ON_CHANNEL_READCOMPLETE(Functions.fromFunc(HttpFuncs.ON_CHANNEL_READCOMPLETE_FUNC1)),
    ON_CHANNEL_WRITABILITYCHANGED(Functions.fromFunc(HttpFuncs.ON_CHANNEL_WRITABILITYCHANGED_FUNC1)),
    ;
    
    private HttpHandlers(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
    
    @Override
    public FuncN<ChannelHandler> factory() {
        return this._factory;
    }

    private static final ToOrdinal TO_ORDINAL = Nettys.ordinal(HttpHandlers.class);
    
    @Override
    public ToOrdinal toOrdinal() {
        return TO_ORDINAL;
    }
}
