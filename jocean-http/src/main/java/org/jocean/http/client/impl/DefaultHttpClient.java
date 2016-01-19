/**
 * 
 */
package org.jocean.http.client.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
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
import org.jocean.idiom.rx.RxFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;
import rx.functions.Functions;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {
    
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
        return Observable.create(new OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<Object> responseSubscriber) {
                if (!responseSubscriber.isUnsubscribed()) {
                    try {
                        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<Subscription>();
                        final Action1<Subscription> add4release = new Action1<Subscription>() {
                            @Override
                            public void call(final Subscription subscription) {
                                if ( null == subscriptionRef.get()) {
                                    subscriptionRef.set(subscription);
                                } else {
                                    subscriptionRef.set(Subscriptions.from(subscriptionRef.get(), subscription));
                                }
                            }};
                        
                        final Feature[] fullFeatures = buildFeatures(applyFeatures, responseSubscriber);
                        _channelPool.retainChannel(remoteAddress, add4release)
                            .doOnNext(prepareReuseChannel(fullFeatures, add4release))
                            .onErrorResumeNext(createChannel(remoteAddress, fullFeatures, add4release))
                            .doOnNext(attachSubscriberToChannel(responseSubscriber, add4release))
                            .doOnNext(fillChannelAware(
                                    InterfaceUtils.compositeIncludeType(ChannelAware.class, 
                                            (Object[])applyFeatures)))
                            .flatMap(doTransferRequest(request, applyFeatures))
                            .flatMap(RxNettys.<ChannelFuture, Object>emitErrorOnFailure())
//                            .doOnNext(new Action1<ChannelFuture>() {
//                                @Override
//                                public void call(final ChannelFuture future) {
//                                    responseSubscriber.add(Subscriptions.from(future));
//                                    future.addListener(RxNettys.makeFailure2ErrorListener(responseSubscriber));
//                                }})
                            .doOnUnsubscribe(new Action0() {
                                @Override
                                public void call() {
                                    final Subscription subscription = subscriptionRef.getAndSet(null);
                                    if (null!=subscription) {
                                        subscription.unsubscribe();
                                    }
                                }})
                            .subscribe(responseSubscriber);
                    } catch (final Throwable e) {
                        responseSubscriber.onError(e);
                    }
                } else {
                    LOG.warn("defineInteraction: responseSubscriber {} has unsubscribe", responseSubscriber);
                }
            }});
    }

    private Action1<? super Channel> attachSubscriberToChannel(
            final Subscriber<Object> subscriber,
            final Action1<Subscription> add4release) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                final ChannelInboundHandler handler = new OnSubscribeHandler(subscriber);
                channel.pipeline().addLast(handler);
                
                add4release.call(
                    Subscriptions.create(
                        new Action0() {
                            @Override
                            public void call() {
                                final ChannelPipeline pipeline = channel.pipeline();
                                if (pipeline.context(handler) != null) {
                                    pipeline.remove(handler);
                                }
                            }}));
            }};
    }

    private Func1<Channel, Observable<ChannelFuture>> doTransferRequest(
            final Observable<? extends Object> request,
            final Feature[] applyFeatures) {
        return new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(doApplyToRequest(applyFeatures))
                        .doOnNext(doForChannelPool(channel))
                        .map(RxNettys.<Object>sendMessage(channel));
            }
        };
    }

    private Action1<Object> doApplyToRequest(final Feature[] applyFeatures) {
        final ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeIncludeType(
                    ApplyToRequest.class,
                    InterfaceUtils.compositeBySource(
                        ApplyToRequest.class, _CLS2APPLYTOREQUEST, applyFeatures),
                    InterfaceUtils.compositeIncludeType(
                        ApplyToRequest.class, (Object[])applyFeatures));
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest && null!=applyToRequest) {
                    applyToRequest.call((HttpRequest) msg);
                }
            }
        };
    }
    
    private final Action1<Object> doForChannelPool(final Channel channel) {
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest) {
                    _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                }
            }
        };
    }

    private Observable<? extends Channel> createChannel(
            final SocketAddress remoteAddress, 
            final Feature[] features, 
            final Action1<Subscription> add4release) {
        Observable<? extends Channel> channelObservable = Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> channelSubscriber) {
                if (!channelSubscriber.isUnsubscribed()) {
                    final ChannelFuture future = _channelCreator.newChannel();
                    ChannelPool.Util.attachChannelPool(future.channel(), _channelPool);
                    ChannelPool.Util.attachIsReady(future.channel(), IS_READY);
                    add4release.call(recycleChannelSubscription(future.channel()));
                    add4release.call(Subscriptions.from(future));
                    future.addListener(RxNettys.makeFailure2ErrorListener(channelSubscriber));
                    future.addListener(RxNettys.makeSuccess2NextCompletedListener(channelSubscriber));
                } else {
                    LOG.warn("newChannel: channelSubscriber {} has unsubscribe", channelSubscriber);
                }
            }})
            .flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    return Observable.create(new OnSubscribe<Channel>() {
                        @Override
                        public void call(final Subscriber<? super Channel> channelSubscriber) {
                            if (!channelSubscriber.isUnsubscribed()) {
                                applyNononeoffFeatures(channel, features);
                                add4release.call(applyOneoffFeatures(channel, features));
                                final ChannelFuture future = channel.connect(remoteAddress);
                                add4release.call(Subscriptions.from(future));
                                future.addListener(RxNettys.makeFailure2ErrorListener(channelSubscriber));
                                future.addListener(RxNettys.makeSuccess2NextCompletedListener(channelSubscriber));
                            } else {
                                LOG.warn("applyFeatures: channelSubscriber {} has unsubscribe", channelSubscriber);
                            }
                        }});
                }});
        if (isSSLEnabled(features)) {
            channelObservable = channelObservable.flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    return Observable.create(new OnSubscribe<Channel>() {
                        @Override
                        public void call(final Subscriber<? super Channel> channelSubscriber) {
                            if (!channelSubscriber.isUnsubscribed()) {
                                channel.pipeline().addLast(buildSslHandshakeNotifier(channelSubscriber));
                            } else {
                                LOG.warn("SslHandshakeNotifier: channelSubscriber {} has unsubscribe", channelSubscriber);
                            }
                        }});
                }});
        } else {
            channelObservable = channelObservable.doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    setChannelReady(channel);
                }});
        }
        return channelObservable;
    }

    private ChannelInboundHandlerAdapter buildSslHandshakeNotifier(
            final Subscriber<? super Channel> channelSubscriber) {
        return new ChannelInboundHandlerAdapter() {
            private void removeSelf(final ChannelHandlerContext ctx) {
                final ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.context(this) != null) {
                    pipeline.remove(this);
                }
            }

            @Override
            public void userEventTriggered(final ChannelHandlerContext ctx,
                    final Object evt) throws Exception {
                if (evt instanceof SslHandshakeCompletionEvent) {
                    removeSelf(ctx);
                    final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                    if (sslComplete.isSuccess()) {
                        setChannelReady(ctx.channel());
                        channelSubscriber.onNext(ctx.channel());
                        channelSubscriber.onCompleted();
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("channel({}): userEventTriggered for ssl handshake success",
                                    ctx.channel());
                        }
                    } else {
                        channelSubscriber.onError(sslComplete.cause());
                        LOG.warn("channel({}): userEventTriggered for ssl handshake failure:{}",
                                ctx.channel(), ExceptionUtils.exception2detail(sslComplete.cause()));
                    }
                }
                ctx.fireUserEventTriggered(evt);
            }
        };
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

    private Subscription recycleChannelSubscription(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                if (channel.eventLoop().inEventLoop()) {
                    ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                        }});
                }
            }
        });
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
    
    private Feature[] buildFeatures(
            Feature[] features,
            final Subscriber<Object> responseSubscriber) {
        features = JOArrays.addFirst(Feature[].class, features, APPLY_HTTPCLIENT);
        final ResponseSubscriberAware responseSubscriberAware = 
                InterfaceUtils.compositeIncludeType(ResponseSubscriberAware.class, (Object[])features);
        if (null!=responseSubscriberAware) {
            responseSubscriberAware.setResponseSubscriber(responseSubscriber);
        }
        return features;
    }

    private Action1<Channel> fillChannelAware(final ChannelAware channelAware) {
        return new Action1<Channel>() {
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
            }};
    }

    private Action1<Channel> prepareReuseChannel(
            final Feature[] features,
            final Action1<Subscription> add4release) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                add4release.call(recycleChannelSubscription(channel));
                add4release.call(applyOneoffFeatures(channel, features));
            }};
    }

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
    
    private static boolean isSSLEnabled(final Feature[] features) {
        if (null == features) {
            return false;
        }
        for (Feature feature : features) {
            if (feature instanceof ENABLE_SSL) {
                return true;
            }
        }
        return false;
    }

    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;
    
    private static final AttributeKey<Object> READY_ATTR = AttributeKey.valueOf("__READY");
    
    private static final Func1<Channel,Boolean> IS_READY = new Func1<Channel,Boolean>() {
        @Override
        public Boolean call(final Channel channel) {
            return null != channel.attr(READY_ATTR).get();
        }};
        
    private static void setChannelReady(final Channel channel) {
        channel.attr(READY_ATTR).set(new Object());
    }
        
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

    private static enum APPLY implements PipelineApply {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        PROGRESSIVE(Functions.fromFunc(PROGRESSIVE_FUNC2)),
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
    
    static {
        _APPLY_BUILDER_ONEOFF = new Class2ApplyBuilder();
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_DECOMPRESSOR);
        _APPLY_BUILDER_ONEOFF.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER_ONEOFF.register(Outbound.ENABLE_PROGRESSIVE.class, APPLY.PROGRESSIVE);
        _APPLY_BUILDER_ONEOFF.register(Outbound.ENABLE_MULTIPART.getClass(), APPLY.CHUNKED_WRITER);
        
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
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
