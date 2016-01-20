package org.jocean.http.client.impl;

import org.jocean.http.Feature;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.PipelineApply;
import org.jocean.idiom.rx.RxFunctions;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import rx.functions.FuncN;
import rx.functions.Functions;

final class HttpClientConstants {
    final static Feature APPLY_HTTPCLIENT = new Feature.AbstractFeature0() {};

    static final Class2Instance<Feature, ApplyToRequest> _CLS_TO_APPLY2REQ;
    
    static final Class2ApplyBuilder _APPLY_BUILDER_PER_INTERACTION;
        
    static final Class2ApplyBuilder _APPLY_BUILDER_PER_CHANNEL;
    

    static enum APPLY implements PipelineApply {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        INTERACTIONMETER(INTERACTIONMETER_FUNCN),
//        PROGRESSIVE(Functions.fromFunc(PROGRESSIVE_FUNC2)),
        CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC1)),
        SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
        HTTPCLIENT(HTTPCLIENT_CODEC_FUNCN),
        CONTENT_DECOMPRESSOR(CONTENT_DECOMPRESSOR_FUNCN),
        CHUNKED_WRITER(CHUNKED_WRITER_FUNCN)
        ;
        
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
    
    private static final FuncN<ChannelHandler> INTERACTIONMETER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new InteractionMeterHandler();
        }};
        
    private static final FuncN<ChannelHandler> HTTPCLIENT_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpClientCodec();
        }};
            
    private static final FuncN<ChannelHandler> CONTENT_DECOMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentDecompressor();
        }};
        
    private static final FuncN<ChannelHandler> CHUNKED_WRITER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new ChunkedWriteHandler();
        }};

    /*
    private static final Func2<Subscriber<Object>, Long, ChannelHandler> PROGRESSIVE_FUNC2 = 
            new Func2<Subscriber<Object>, Long, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Subscriber<Object> subscriber,
                final Long minIntervalInMs) {
            return new ChannelDuplexHandler() {
                long _lastTimestamp = -1;
                long _uploadProgress = 0;
                long _downloadProgress = 0;

                private void onNext4UploadProgress(
                        final Subscriber<Object> subscriber) {
                    final long uploadProgress = this._uploadProgress;
                    this._uploadProgress = 0;
                    subscriber.onNext(new HttpClient.UploadProgressable() {
                        @Override
                        public long progress() {
                            return uploadProgress;
                        }
                    });
                }

                private void notifyUploadProgress(final ByteBuf byteBuf) {
                    this._uploadProgress += byteBuf.readableBytes();
                    final long now = System.currentTimeMillis();
                    if (this._lastTimestamp > 0
                            && (now - this._lastTimestamp) < minIntervalInMs) {
                        return;
                    }
                    this._lastTimestamp = now;
                    onNext4UploadProgress(subscriber);
                }

                private void notifyDownloadProgress(final ByteBuf byteBuf) {
                    if (this._uploadProgress > 0) {
                        onNext4UploadProgress(subscriber);
                    }

                    this._downloadProgress += byteBuf.readableBytes();
                    final long now = System.currentTimeMillis();
                    if (this._lastTimestamp > 0
                            && (now - this._lastTimestamp) < minIntervalInMs) {
                        return;
                    }
                    this._lastTimestamp = now;
                    final long downloadProgress = this._downloadProgress;
                    this._downloadProgress = 0;
                    subscriber.onNext(new HttpClient.DownloadProgressable() {
                        @Override
                        public long progress() {
                            return downloadProgress;
                        }
                    });
                }

                @Override
                public void channelRead(final ChannelHandlerContext ctx,
                        final Object msg) throws Exception {
                    if (msg instanceof ByteBuf) {
                        notifyDownloadProgress((ByteBuf) msg);
                    } else if (msg instanceof ByteBufHolder) {
                        notifyDownloadProgress(((ByteBufHolder) msg).content());
                    }
                    ctx.fireChannelRead(msg);
                }

                @Override
                public void write(final ChannelHandlerContext ctx, Object msg,
                        final ChannelPromise promise) throws Exception {
                    if (msg instanceof ByteBuf) {
                        notifyUploadProgress((ByteBuf) msg);
                    } else if (msg instanceof ByteBufHolder) {
                        notifyUploadProgress(((ByteBufHolder) msg).content());
                    }
                    ctx.write(msg, promise);
                }
            };
        }

    };
    */
    
    static {
        _APPLY_BUILDER_PER_INTERACTION = new Class2ApplyBuilder();
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_DECOMPRESSOR);
        _APPLY_BUILDER_PER_INTERACTION.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
//        _APPLY_BUILDER_PER_INTERACTION.register(Outbound.ENABLE_PROGRESSIVE.class, APPLY.PROGRESSIVE);
        _APPLY_BUILDER_PER_INTERACTION.register(Outbound.ENABLE_MULTIPART.getClass(), APPLY.CHUNKED_WRITER);
        _APPLY_BUILDER_PER_INTERACTION.register(InteractionMeterProxy.class, APPLY.INTERACTIONMETER);
        
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
