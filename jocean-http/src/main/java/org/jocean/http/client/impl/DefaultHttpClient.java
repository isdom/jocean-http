/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.client.Outbound.ResponseSubscriberAware;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.PipelineApply;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.rx.OneshotSubscription;
import org.jocean.idiom.rx.RxFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;
import rx.functions.Functions;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {
    
    public interface FeaturesAware {
        public void setApplyFeatures(final Feature[] features);
    }
    
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClient.class);
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.HttpClient#sendRequest(java.net.URI, rx.Observable)
     * eg: new SocketAddress(this._uri.getHost(), this._uri.getPort()))
     */
    @Override
    public Observable<? extends Object> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final Feature... features) {
        final Feature[] applyFeatures = cloneFeatures(features.length > 0 ? features : this._defaultFeatures);
        final ApplyToRequest applyToRequest = 
            InterfaceUtils.compositeIncludeType(
                ApplyToRequest.class,
                InterfaceUtils.compositeBySource(
                    ApplyToRequest.class, _CLS2APPLYTOREQUEST, applyFeatures),
                InterfaceUtils.compositeIncludeType(
                    ApplyToRequest.class, (Object[])applyFeatures));
        final Func1<Channel, Observable<ChannelFuture>> transferRequest = 
                new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(doWhenRequest(channel))
                        .map(RxNettys.<Object>sendMessage(channel));
            }
            private final Action1<Object> doWhenRequest(final Channel channel) {
                return new Action1<Object> () {
                    @Override
                    public void call(final Object msg) {
                        if (msg instanceof HttpRequest) {
                            _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                            if (null!=applyToRequest) {
                                applyToRequest.call((HttpRequest) msg);
                            }
                        }
                    }
                };
            }
        };
        final ChannelAware channelAware = InterfaceUtils.compositeIncludeType(ChannelAware.class, 
                (Object[])applyFeatures);
        return Observable.create(new OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<Object> responseSubscriber) {
                if (!responseSubscriber.isUnsubscribed()) {
                    try {
                        final Feature[] features = buildFeatures(applyFeatures, responseSubscriber);
                        _channelPool.retainChannel(remoteAddress)
                            .doOnNext(new Action1<Channel>() {
                                @Override
                                public void call(Channel channel) {
                                    responseSubscriber.add(applyOneoffFeatures(channel, features));
                                }})
                            .onErrorResumeNext(createChannel(remoteAddress, features))
                            .doOnNext(new Action1<Channel>() {
                                @Override
                                public void call(final Channel channel) {
                                    if (null!=channelAware) {
                                        try {
                                            channelAware.setChannel(channel);
                                        } catch (Exception e) {
                                            LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                                                    channel, ExceptionUtils.exception2detail(e));
                                        }
                                    }
                                }})
                            .flatMap(transferRequest)
                            .flatMap(RxNettys.<ChannelFuture, Object>emitErrorOnFailure())
                            .subscribe(responseSubscriber);
                    } catch (final Throwable e) {
                        responseSubscriber.onError(e);
                    }
                }
            }});
    }

    private Observable<? extends Channel> createChannel(
            final SocketAddress remoteAddress, 
            final Feature[] features) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> channelSubscriber) {
                prepareChannelSubscriberAware(channelSubscriber, features);
                prepareFeaturesAware(features);

                final ChannelFuture future = _channelCreator.newChannel();
                channelSubscriber.add(recycleChannelSubscription(future.channel()));
                RxNettys.<ChannelFuture,Channel>emitErrorOnFailure()
                    .call(future)
                    .subscribe(channelSubscriber);
                RxNettys.emitNextAndCompletedOnSuccess()
                    .call(future)
                    .flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                        @Override
                        public Observable<? extends Channel> call(final Channel channel) {
                            ChannelPool.Util.attachChannelPool(channel, _channelPool);
                            ChannelPool.Util.attachIsReady(channel, IS_READY);
                            applyNononeoffFeatures(channel, features);
                            channelSubscriber.add(
                                applyOneoffFeatures(channel, features));
                            return RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
                                .call(channel.connect(remoteAddress));
                        }})
                    .subscribe(channelSubscriber);
            }});
    }

    private Feature[] cloneFeatures(final Feature[] features) {
        final Feature[] cloned = new Feature[features.length];
        for (int idx = 0; idx < cloned.length; idx++) {
            if (features[idx] instanceof Cloneable) {
                cloned[idx] = ReflectUtils.invokeClone(features[idx]);
            } else {
                cloned[idx] = features[idx];
            }
        }
        return cloned;
    }

    private void prepareFeaturesAware(final Feature[] features) {
        final FeaturesAware featuresAware = 
                InterfaceUtils.compositeIncludeType(FeaturesAware.class, (Object[])features);
        if (null!=featuresAware) {
            featuresAware.setApplyFeatures(features);
        }
    }

    private void prepareChannelSubscriberAware(
            final Subscriber<? super Channel> subscriber,
            final Feature[] features) {
        final ChannelSubscriberAware channelSubscriberAware = 
                InterfaceUtils.compositeIncludeType(ChannelSubscriberAware.class, (Object[])features);
        if (null!=channelSubscriberAware) {
            channelSubscriberAware.setChannelSubscriber(subscriber);
        }
    }
    
    private Subscription recycleChannelSubscription(final Channel channel) {
        return new OneshotSubscription() {
            @Override
            protected void doUnsubscribe() {
                if (channel.eventLoop().inEventLoop()) {
                    ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                        }});
                }
            }};
    }
    
    public DefaultHttpClient(final int processThreadNumber) {
        this(processThreadNumber, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient() {
        this(0, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient(final Feature... defaultFeatures) {
        this(0, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final Feature... defaultFeatures) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            new DefaultChannelPool(), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final Feature... defaultFeatures) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }},
            new DefaultChannelPool(),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final Feature... defaultFeatures) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }},
            new DefaultChannelPool(),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final Feature... defaultFeatures) {
        this(channelCreator, new DefaultChannelPool(), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final ChannelPool channelPool,
            final Feature... defaultFeatures) {
        this._channelCreator = channelCreator;
        this._channelPool = channelPool;
        this._defaultFeatures = (null != defaultFeatures) ? defaultFeatures : Feature.EMPTY_FEATURES;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }

    private final static Feature APPLY_HTTPCLIENT = new Feature.AbstractFeature0() {};
    
    private static final class APPLY_READY4INTERACTION_NOTIFIER implements
            Feature, ChannelSubscriberAware, FeaturesAware {
        @Override
        public void setChannelSubscriber(
                final Subscriber<? super Channel> subscriber) {
            this._channelSubscriber = subscriber;
        }
        
        @Override
        public void setApplyFeatures(final Feature[] features) {
            for (Feature feature : features) {
                if (feature instanceof ENABLE_SSL) {
                    this._isSSLEnabled = true;
                }
            }
        }

        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline,
                    this._isSSLEnabled, this._channelSubscriber);
        }

        private boolean _isSSLEnabled = false;
        private Subscriber<? super Channel> _channelSubscriber;
    }

    private static final class APPLY_WORKER implements Feature,
            ResponseSubscriberAware {

        @Override
        public void setResponseSubscriber(final Subscriber<Object> subscriber) {
            this._responseSubscriber = subscriber;
        }

        @Override
        public ChannelHandler call(final HandlerBuilder builder, final ChannelPipeline pipeline) {
            return builder.build(this, pipeline, this._responseSubscriber);
        }

        private Subscriber<Object> _responseSubscriber;
    }
    
    private Feature[] buildFeatures(
            Feature[] features,
            final Subscriber<Object> responseSubscriber) {
        features = JOArrays.addFirst(Feature[].class, features, 
                APPLY_HTTPCLIENT, new APPLY_READY4INTERACTION_NOTIFIER(), new APPLY_WORKER());
        final ResponseSubscriberAware responseSubscriberAware = 
                InterfaceUtils.compositeIncludeType(ResponseSubscriberAware.class, (Object[])features);
        if (null!=responseSubscriberAware) {
            responseSubscriberAware.setResponseSubscriber(responseSubscriber);
        }
        return features;
    }

    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;
    
    private static void applyNononeoffFeatures(
            final Channel channel,
            final Feature[] features) {
        InterfaceUtils.combineImpls(Feature.class, features)
            .call(_APPLY_BUILDER, channel.pipeline());
    }

    private static Subscription applyOneoffFeatures(
            final Channel channel,
            final Feature[] features) {
        final Func0<String[]> diff = Nettys.namesDifferenceBuilder(channel);
        InterfaceUtils.combineImpls(Feature.class, features)
            .call(_APPLY_BUILDER_ONEOFF, channel.pipeline());
        return RxNettys.removeHandlersSubscription(channel, diff.call());
    }
    
    private static final Func1<Channel,Boolean> IS_READY = new Func1<Channel,Boolean>() {
        @Override
        public Boolean call(final Channel channel) {
            return (channel.pipeline().names().indexOf(APPLY.READY4INTERACTION_NOTIFIER.name()) == -1);
        }};
        
    private static final Class2Instance<Feature, ApplyToRequest> _CLS2APPLYTOREQUEST;
    
    private static final Class2ApplyBuilder _APPLY_BUILDER_ONEOFF;
        
    private static final Class2ApplyBuilder _APPLY_BUILDER;
    

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

    private static final class Ready4InteractionNotifier extends
            ChannelInboundHandlerAdapter {
        private final boolean _enableSSL;
        private final Subscriber<? super Channel> _subscriber;

        private Ready4InteractionNotifier(final boolean enableSSL,
                final Subscriber<? super Channel> subscriber) {
            this._enableSSL = enableSSL;
            this._subscriber = subscriber;
        }

        private void removeSelf(final ChannelHandlerContext ctx) {
            final ChannelPipeline pipeline = ctx.pipeline();
            if (pipeline.context(this) != null) {
                pipeline.remove(this);
            }
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx)
                throws Exception {
            if (!_enableSSL) {
                removeSelf(ctx);
                _subscriber.onNext(ctx.channel());
                _subscriber.onCompleted();
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "channel({}): Ready4InteractionNotifier.channelActive",
                            ctx.channel());
                }
            }
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx,
                final Object evt) throws Exception {
            if (_enableSSL && evt instanceof SslHandshakeCompletionEvent) {
                final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                if (sslComplete.isSuccess()) {
                    removeSelf(ctx);
                    _subscriber.onNext(ctx.channel());
                    _subscriber.onCompleted();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "channel({}): Ready4InteractionNotifier.userEventTriggered for ssl handshake success",
                                ctx.channel());
                    }
                } else {
                    _subscriber.onError(sslComplete.cause());
                    LOG.warn(
                            "channel({}): Ready4InteractionNotifier.userEventTriggered for ssl handshake failure:{}",
                            ctx.channel(), ExceptionUtils
                                    .exception2detail(sslComplete.cause()));
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    private static final Func2<Boolean, Subscriber<? super Channel>, ChannelHandler> READY4INTERACTION_NOTIFIER_FUNC2 = 
            new Func2<Boolean, Subscriber<? super Channel>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Boolean isSSLEnabled,
                final Subscriber<? super Channel> subscriber) {
            return new Ready4InteractionNotifier(isSSLEnabled, subscriber);
        }
    };

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

    private static final Func1<Subscriber<? super Object>, ChannelHandler> HTTPCLIENT_WORK_FUNC1 = 
            new Func1<Subscriber<? super Object>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Subscriber<? super Object> subscriber) {
            return new SimpleChannelInboundHandler<HttpObject>() {
                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channelInactive: ch({})", ctx.channel());
                    }
                    ctx.fireChannelInactive();
                    subscriber.onError(new RuntimeException("peer has closed."));
                }

                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx,
                        final Throwable cause) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("exceptionCaught: ch({}), detail:{}",
                                ctx.channel(),
                                ExceptionUtils.exception2detail(cause));
                    }
                    ctx.close();
                    subscriber.onError(cause);
                }

                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject msg) throws Exception {
                    subscriber.onNext(msg);
                    if (msg instanceof LastHttpContent) {
                        /*
                         * netty 参考代码:
                         * https://github.com/netty/netty/blob/netty-
                         * 4.0.26.Final /codec/src
                         * /main/java/io/netty/handler/codec
                         * /ByteToMessageDecoder .java#L274
                         * https://github.com/netty
                         * /netty/blob/netty-4.0.26.Final /codec-http
                         * /src/main/java
                         * /io/netty/handler/codec/http/HttpObjectDecoder
                         * .java#L398 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件
                         * currentState == State.READ_VARIABLE_LENGTH_CONTENT &&
                         * !in.isReadable() && !chunked
                         * 即没有指定Content-Length头域，也不是CHUNKED传输模式
                         * ，此情况下，即会自动产生一个LastHttpContent .EMPTY_LAST_CONTENT实例
                         * 因此，无需在channelInactive处，针对该情况做特殊处理
                         */
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(
                                    "channelRead0: ch({}) recv LastHttpContent:{}",
                                    ctx.channel(), msg);
                        }
                        final ChannelPool pool = ChannelPool.Util
                                .getChannelPool(ctx.channel());
                        if (null != pool) {
                            pool.afterReceiveLastContent(ctx.channel());
                        }
                        subscriber.onCompleted();
                    }
                }
            };
        }
    };
        
    private static enum APPLY implements PipelineApply {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        PROGRESSIVE(Functions.fromFunc(PROGRESSIVE_FUNC2)),
        CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC1)),
        SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
        HTTPCLIENT(HTTPCLIENT_CODEC_FUNCN),
        CONTENT_DECOMPRESSOR(CONTENT_DECOMPRESSOR_FUNCN),
        CHUNKED_WRITER(CHUNKED_WRITER_FUNCN),
        READY4INTERACTION_NOTIFIER(Functions.fromFunc(READY4INTERACTION_NOTIFIER_FUNC2)),
        WORKER(Functions.fromFunc(HTTPCLIENT_WORK_FUNC1)),
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
    
    static {
        _APPLY_BUILDER_ONEOFF = new Class2ApplyBuilder();
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_DECOMPRESSOR);
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER_ONEOFF.register(Outbound.ENABLE_PROGRESSIVE.class, APPLY.PROGRESSIVE);
        _APPLY_BUILDER_ONEOFF.register(Outbound.ENABLE_MULTIPART.getClass(), APPLY.CHUNKED_WRITER);
        _APPLY_BUILDER_ONEOFF.register(APPLY_WORKER.class, APPLY.WORKER);
        
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
        _APPLY_BUILDER.register(APPLY_READY4INTERACTION_NOTIFIER.class, APPLY.READY4INTERACTION_NOTIFIER);
        _APPLY_BUILDER.register(APPLY_HTTPCLIENT.getClass(), APPLY.HTTPCLIENT);
        
        _CLS2APPLYTOREQUEST = new Class2Instance<>();
        _CLS2APPLYTOREQUEST.register(Feature.ENABLE_COMPRESSOR.getClass(), 
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
