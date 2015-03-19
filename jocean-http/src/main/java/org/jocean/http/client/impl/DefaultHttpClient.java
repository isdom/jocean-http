/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.client.HttpClient;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {
    
    private static final String RESPONSE_HANDLER = "RESPONSE";
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
    public Observable<HttpObject> sendRequest(
            final SocketAddress remoteAddress,
            final Observable<? extends HttpObject> request,
            final Feature... features) {
        final int featuresAsInt = this._defaultFeaturesAsInt | Features.featuresAsInt(features);
        return Observable.create(
            new OnSubscribeResponse(
                new Func1<ChannelHandler, Observable<Channel>> () {
                    @Override
                    public Observable<Channel> call(final ChannelHandler handler) {
                        return createChannelObservable(remoteAddress, featuresAsInt, handler);
                    }},
                this._channelPool,
                Feature.isCompressEnabled(featuresAsInt), 
                request));
    }

    private Observable<Channel> createChannelObservable(
            final SocketAddress remoteAddress, 
            final int featuresAsInt,
            final ChannelHandler handler) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                try {
                    if (!subscriber.isUnsubscribed()) {
                        if (!reuseChannel(remoteAddress, featuresAsInt, handler, subscriber)) {
                            createChannel(remoteAddress, featuresAsInt, handler, subscriber);
                        }
                    }
                } catch (Throwable e) {
                    subscriber.onError(e);
                }
            }});
    }

    private boolean reuseChannel(
            final SocketAddress remoteAddress,
            final int featuresAsInt, 
            final ChannelHandler handler,
            final Subscriber<? super Channel> subscriber) {
        final Channel channel = this._channelPool.retainChannel(remoteAddress);
        if (null!=channel) {
            final HandlersClosure handlersClosure = 
                    Nettys.channelHandlersClosure(channel);
            addFeatureCodecs(channel, featuresAsInt, handlersClosure)
                .addLast(RESPONSE_HANDLER, handlersClosure.call(handler));
            
            subscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
            subscriber.onNext(channel);
            subscriber.onCompleted();
            return true;
        }
        else {
            return false;
        }
    }

    private void createChannel(
            final SocketAddress remoteAddress,
            final int featuresAsInt, 
            final ChannelHandler handler,
            final Subscriber<? super Channel> subscriber) throws Throwable {
        final Channel channel = newChannel();
        final boolean enableSSL = Features.isEnabled(featuresAsInt, Feature.EnableSSL);
        try {
            final HandlersClosure handlersClosure = 
                    Nettys.channelHandlersClosure(channel);
            addHttpClientCodecs(channel, enableSSL, subscriber, handlersClosure);
            addFeatureCodecs(channel, featuresAsInt, handlersClosure)
                .addLast(RESPONSE_HANDLER, handlersClosure.call(handler));
        
            subscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
            subscriber.add(Subscriptions.from(
                channel.connect(remoteAddress)
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)));
        } catch (Throwable e) {
            if (null!=channel) {
                channel.close();
            }
            throw e;
        }
    }

    private Channel newChannel() throws Exception {
        final Channel ch = _bootstrap.register().channel();
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("create new channel: {}", ch);
        }
        return ch;
    }
    
    private ChannelPipeline addFeatureCodecs(
            final Channel channel,
            final int featuresAsInt,
            final HandlersClosure handlersClosure) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (Features.isEnabled(featuresAsInt, Feature.EnableLOG)) {
            //  add first
            pipeline.addFirst("log", 
                handlersClosure.call(new LoggingHandler()));
        }
                  
        if (Feature.isCompressEnabled(featuresAsInt)) {
            //  add last
            pipeline.addLast("decompressor", 
                handlersClosure.call(new HttpContentDecompressor()));
        }
        
        return pipeline;
    }
    
    private ChannelPipeline addHttpClientCodecs(
            final Channel channel,
            final boolean enableSSL,
            final Subscriber<? super Channel> subscriber,
            final HandlersClosure handlersClosure) {
        final ChannelPipeline pipeline = channel.pipeline();
        
        // Enable SSL if necessary.
        if (enableSSL) {
            pipeline.addLast("ssl", _sslCtx.newHandler(channel.alloc()));
        }
        
        pipeline.addLast("completeOrErrorNotifier", 
                handlersClosure.call(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    if (!enableSSL) {
                        subscriber.onNext(channel);
                        subscriber.onCompleted();
                    }
                    ctx.fireChannelActive();
                }
                
                @Override
                public void userEventTriggered(
                    final ChannelHandlerContext ctx,
                    final Object evt) throws Exception {
                    if (enableSSL && evt instanceof SslHandshakeCompletionEvent) {
                        final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                        if (sslComplete.isSuccess()) {
                            subscriber.onNext(channel);
                            subscriber.onCompleted();
                        } else {
                            subscriber.onError(sslComplete.cause());
                        }
                    }
                    ctx.fireUserEventTriggered(evt);
                }
            }));
            
        pipeline.addLast("clientCodec", new HttpClientCodec());
                  
        return pipeline;
    }

    private Subscription channelClosure(
            final SocketAddress remoteAddress,
            final Channel channel, 
            final HandlersClosure handlersClosure) {
        return new Subscription() {
            final AtomicBoolean _isUnsubscribed = new AtomicBoolean(false);
            @Override
            public void unsubscribe() {
                if (_isUnsubscribed.compareAndSet(false, true)) {
                    try {
                        handlersClosure.close();
                    } catch (IOException e) {
                    }
                    if (!_channelPool.recycleChannel(remoteAddress, channel)) {
                        channel.close();
                    }
                }
            }
            @Override
            public boolean isUnsubscribed() {
                return _isUnsubscribed.get();
            }};
    }

    public DefaultHttpClient() throws Exception {
        this(1);
    }
    
    public DefaultHttpClient(final int processThreadNumber) throws Exception {
        this(new DefaultChannelPool(), 
            new NioEventLoopGroup(processThreadNumber), 
            NioSocketChannel.class);
    }
    
    private static final class BootstrapChannelFactory<T extends Channel> implements ChannelFactory<T> {
        private final Class<? extends T> clazz;

        BootstrapChannelFactory(Class<? extends T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public T newChannel() {
            try {
                return clazz.newInstance();
            } catch (Throwable t) {
                throw new ChannelException("Unable to create Channel from class " + clazz, t);
            }
        }

        @Override
        public String toString() {
            return StringUtil.simpleClassName(clazz) + ".class";
        }
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final Feature... defaultFeatures) throws Exception { 
        this(new DefaultChannelPool(),
            eventLoopGroup, 
            new BootstrapChannelFactory<Channel>(channelType), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final Feature... defaultFeatures) throws Exception { 
        this(new DefaultChannelPool(), 
            eventLoopGroup, 
            channelFactory, 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final Feature... defaultFeatures) throws Exception { 
        this(channelPool,
            eventLoopGroup, 
            new BootstrapChannelFactory<Channel>(channelType), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final Feature... defaultFeatures) throws Exception { 
        this._channelPool = channelPool;
        this._defaultFeaturesAsInt = Features.featuresAsInt(defaultFeatures);
        // Configure the client.
        this._bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channelFactory(channelFactory)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(final Channel channel) throws Exception {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            ctx.fireChannelActive();
                            _activeChannelCount.incrementAndGet();
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            ctx.fireChannelInactive();
                            _activeChannelCount.decrementAndGet();
                        }
                    });
                }})
            ;
        this._sslCtx = SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._bootstrap.group().shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
    
    public int getActiveChannelCount() {
        return this._activeChannelCount.get();
    }
    
    private final AtomicInteger _activeChannelCount = new AtomicInteger(0);
    private final Bootstrap _bootstrap;
    private final SslContext _sslCtx;
    private final ChannelPool _channelPool;
    private final int _defaultFeaturesAsInt;
}
