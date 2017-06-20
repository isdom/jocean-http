package org.jocean.redis.impl;

import org.jocean.http.util.CommonFuncs;
import org.jocean.http.util.HandlerPrototype;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ToOrdinal;

import io.netty.channel.ChannelHandler;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum RedisHandlers implements HandlerPrototype {
//    ON_CHANNEL_READ(Functions.fromFunc(FACTORYFUNCS.ON_CHANNEL_READ_FUNC1)),
//    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
//    TRAFFICCOUNTER(FACTORYFUNCS.TRAFFICCOUNTER_FUNCN),
//    CLOSE_ON_IDLE(Functions.fromFunc(FACTORYFUNCS.CLOSE_ON_IDLE_FUNC1)),
//    SSL(Functions.fromFunc(FACTORYFUNCS.SSL_FUNC2)),
//    SSLNOTIFY(Functions.fromFunc(FACTORYFUNCS.SSLNOTIFY_FUNC2)),
//    LOGGING_OVER_SSL(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
//    HTTPCLIENT(FACTORYFUNCS.HTTPCLIENT_CODEC_FUNCN),
//    HTTPSERVER(FACTORYFUNCS.HTTPSERVER_CODEC_FUNCN),
//    CONTENT_DECOMPRESSOR(FACTORYFUNCS.CONTENT_DECOMPRESSOR_FUNCN),
//    CONTENT_COMPRESSOR(FACTORYFUNCS.CONTENT_COMPRESSOR_FUNCN),
//    CHUNKED_WRITER(FACTORYFUNCS.CHUNKED_WRITER_FUNCN),
//    HTTPOBJ_SUBSCRIBER(Functions.fromFunc(FACTORYFUNCS.HTTPOBJ_SUBSCRIBER_FUNC1)),
    
    //  Redis Codec
    REDIS_DECODER(RedisFuncs.REDIS_DECODER_FUNCN),
    REDIS_BULKSTRING_AGGREGATOR(RedisFuncs.REDIS_BULKSTRING_AGGREGATOR_FUNCN),
    REDIS_ARRAY_AGGREGATOR(RedisFuncs.REDIS_ARRAY_AGGREGATOR_FUNCN),
    REDIS_ENCODER(RedisFuncs.REDIS_ENCODER_FUNCN),
    
    ON_MESSAGE(Functions.fromFunc(CommonFuncs.ON_MESSAGE_FUNC1)),
    ON_EXCEPTION_CAUGHT(Functions.fromFunc(CommonFuncs.ON_EXCEPTION_CAUGHT_FUNC1)),
    ON_CHANNEL_INACTIVE(Functions.fromFunc(CommonFuncs.ON_CHANNEL_INACTIVE_FUNC1)),
//    ON_CHANNEL_READCOMPLETE(Functions.fromFunc(FACTORYFUNCS.ON_CHANNEL_READCOMPLETE_FUNC1)),
//    ON_CHANNEL_WRITABILITYCHANGED(Functions.fromFunc(FACTORYFUNCS.ON_CHANNEL_WRITABILITYCHANGED_FUNC1)),
    ;
    
    private RedisHandlers(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
    
    @Override
    public FuncN<ChannelHandler> factory() {
        return this._factory;
    }

    private static final ToOrdinal TO_ORDINAL = Nettys.ordinal(RedisHandlers.class);
    
    @Override
    public ToOrdinal toOrdinal() {
        return TO_ORDINAL;
    }
}
