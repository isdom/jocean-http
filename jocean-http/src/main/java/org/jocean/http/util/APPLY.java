package org.jocean.http.util;

import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.idiom.rx.RxFunctions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.logging.LoggingHandler;
import rx.Observable;
import rx.Subscriber;
import rx.Observable.OnSubscribe;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum APPLY implements PipelineApply {
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    TRAFFICCOUNTER(FACTORYFUNCS.TRAFFICCOUNTER_FUNCN),
    CLOSE_ON_IDLE(Functions.fromFunc(FACTORYFUNCS.CLOSE_ON_IDLE_FUNC1)),
    SSL(Functions.fromFunc(FACTORYFUNCS.SSL_FUNC2)),
    HTTPCLIENT(FACTORYFUNCS.HTTPCLIENT_CODEC_FUNCN),
    HTTPSERVER(FACTORYFUNCS.HTTPSERVER_CODEC_FUNCN),
    CONTENT_DECOMPRESSOR(FACTORYFUNCS.CONTENT_DECOMPRESSOR_FUNCN),
    CONTENT_COMPRESSOR(FACTORYFUNCS.CONTENT_COMPRESSOR_FUNCN),
    CHUNKED_WRITER(FACTORYFUNCS.CHUNKED_WRITER_FUNCN),
    ON_CHANNEL_READ(Functions.fromFunc(FACTORYFUNCS.ON_CHANNEL_READ_FUNC1)),
    HTTPOBJ_OBSERVER(Functions.fromFunc(FACTORYFUNCS.HTTPOBJ_OBSERVER_FUNC1)),
    ;
    
    public static Observable<HttpObject> httpobjObservable(final Channel channel) {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    APPLY.HTTPOBJ_OBSERVER.applyTo(channel.pipeline(), subscriber);
                }
            }} );
    }
    
    public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(APPLY.class);
    
    @Override
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
