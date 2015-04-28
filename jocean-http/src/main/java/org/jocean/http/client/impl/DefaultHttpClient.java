/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Ordered;
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
    
    private final class CompleteOrErrorNotifier extends ChannelInboundHandlerAdapter 
        implements Ordered {
        @Override
        public int ordinal() {
            return OutboundFeature.LAST_FEATURE.ordinal() + 1;
        }
        
        private final Subscriber<? super Channel> _subscriber;
        private final boolean _enableSSL;

        private CompleteOrErrorNotifier(
                final Subscriber<? super Channel> subscriber,
                final boolean enableSSL) {
            this._subscriber = subscriber;
            this._enableSSL = enableSSL;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) 
                throws Exception {
            if (!_enableSSL) {
                markChannelValid(ctx.channel());
                _subscriber.onNext(ctx.channel());
                _subscriber.onCompleted();
            }
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) 
                throws Exception {
            if (_enableSSL && evt instanceof SslHandshakeCompletionEvent) {
                final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                if (sslComplete.isSuccess()) {
                    markChannelValid(ctx.channel());
                    _subscriber.onNext(ctx.channel());
                    _subscriber.onCompleted();
                } else {
                    _subscriber.onError(sslComplete.cause());
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }
    
    private static final String WORK_HANDLER = "WORK";
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
            final OutboundFeature.Applicable... features) {
        final OutboundFeature.Applicable[] applyFeatures = 
                features.length > 0 ? features : this._defaultFeatures;
        return Observable.create(
            new OnSubscribeResponse(
                createChannelObservable(remoteAddress, applyFeatures),
                this._channelPool,
                InterfaceUtils.compositeByType(applyFeatures, OutboundFeature.ApplyToRequest.class),
                request));
    }

    private Func1<ChannelHandler, Observable<Channel>> createChannelObservable(
            final SocketAddress remoteAddress, 
            final OutboundFeature.Applicable[] features) {
        return new Func1<ChannelHandler, Observable<Channel>> () {
            @Override
            public Observable<Channel> call(final ChannelHandler workHandler) {
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        try {
                            if (!subscriber.isUnsubscribed()) {
                                final Channel channel = _channelPool.retainChannel(remoteAddress);
                                if (null!=channel) {
                                    onChannelCreated(
                                            channel, 
                                            remoteAddress,
                                            features, 
                                            workHandler, 
                                            subscriber);
                                } else {
                                    _channelCreator.newChannel()
                                    .addListener(new ChannelFutureListener() {
                                        @Override
                                        public void operationComplete(
                                                final ChannelFuture future)
                                                throws Exception {
                                            if (future.isSuccess()) {
                                                onChannelCreated(
                                                        future.channel(),
                                                        remoteAddress,
                                                        features, 
                                                        workHandler, 
                                                        subscriber);
                                            } else {
                                                subscriber.onError(future.cause());
                                            }
                                            
                                        }});
                                }
                            }
                        } catch (Throwable e) {
                            subscriber.onError(e);
                        }
                }});
            }};
    }

    private void onChannelCreated(
            final Channel channel,
            final SocketAddress remoteAddress,
            final OutboundFeature.Applicable[] features,
            final ChannelHandler workHandler,
            final Subscriber<? super Channel> subscriber) {
        final HandlersClosure handlersClosure = 
                Nettys.channelHandlersClosure(channel);
        
        for ( OutboundFeature.Applicable applicable : features) {
            if (applicable.isRemovable()) {
                handlersClosure.call(applicable.call(channel));
            }
        }
        Nettys.insertHandler(
            channel.pipeline(),
            WORK_HANDLER,
            handlersClosure.call(workHandler),
            OutboundFeature.TO_ORDINAL);
        
        subscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
        
        if (isChannelValid(channel)) {
            subscriber.onNext(channel);
            subscriber.onCompleted();
        } else {
            initAndDoConnect(channel, 
                    remoteAddress,
                    features, 
                    subscriber,
                    handlersClosure);
        }
    }
    
    private void initAndDoConnect(
            final Channel channel,
            final SocketAddress remoteAddress,
            final OutboundFeature.Applicable[] features,
            final Subscriber<? super Channel> subscriber,
            final HandlersClosure handlersClosure) {
        for ( OutboundFeature.Applicable applicable : features) {
            if (!applicable.isRemovable()) {
                applicable.call(channel);
            }
        }
        
        Nettys.insertHandler(
            channel.pipeline(),
            OutboundFeature.HTTPCLIENT_CODEC.name(),
            new HttpClientCodec(),
            OutboundFeature.TO_ORDINAL);
        
        Nettys.insertHandler(
            channel.pipeline(),
            "completeOrErrorNotifier",
            handlersClosure.call(
                new CompleteOrErrorNotifier(subscriber, 
                    OutboundFeature.isSSLEnabled(channel.pipeline()))),
            OutboundFeature.TO_ORDINAL);
        
        subscriber.add(Subscriptions.from(
                channel.connect(remoteAddress)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) {
                        if (!future.isSuccess()) {
                            subscriber.onError(future.cause());
                        }
                    }
                })));
        if (LOG.isDebugEnabled()) {
            LOG.debug("createChannel and add codecs success for channel:{}/remoteAddress:{}",channel,remoteAddress);
        }
    }

    /*
    private boolean reuseChannel(
            final SocketAddress remoteAddress,
            final OutboundFeature.Applicable[] features, 
            final ChannelHandler handler,
            final Subscriber<? super Channel> subscriber) {
        final Channel channel = this._channelPool.retainChannel(remoteAddress);
        if (null!=channel) {
            final HandlersClosure handlersClosure = 
                    Nettys.channelHandlersClosure(channel);
            for ( OutboundFeature.Applicable applicable : features) {
                if (applicable.isRemovable()) {
                    handlersClosure.call(applicable.call(channel));
                }
            }
            Nettys.insertHandler(
                channel.pipeline(),
                RESPONSE_HANDLER,
                handlersClosure.call(handler),
                OutboundFeature.TO_ORDINAL);
            
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
            final OutboundFeature.Applicable[] features, 
            final ChannelHandler handler,
            final Subscriber<? super Channel> subscriber) throws Throwable {
        final Channel channel = this._channelCreator.newChannel();
        try {
            final HandlersClosure handlersClosure = 
                    Nettys.channelHandlersClosure(channel);
            
            for ( OutboundFeature.Applicable applicable : features) {
                if (applicable.isRemovable()) {
                    handlersClosure.call(applicable.call(channel));
                } else {
                    applicable.call(channel);
                }
            }
            
            Nettys.insertHandler(
                channel.pipeline(),
                OutboundFeature.HTTPCLIENT_CODEC.name(),
                new HttpClientCodec(),
                OutboundFeature.TO_ORDINAL);
            
            Nettys.insertHandler(
                channel.pipeline(),
                "completeOrErrorNotifier",
                handlersClosure.call(
                    new CompleteOrErrorNotifier(subscriber, 
                        OutboundFeature.isSSLEnabled(channel.pipeline()))),
                OutboundFeature.TO_ORDINAL);
            
            Nettys.insertHandler(
                channel.pipeline(),
                RESPONSE_HANDLER,
                handlersClosure.call(handler),
                OutboundFeature.TO_ORDINAL);
        
            subscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
            subscriber.add(Subscriptions.from(
                channel.connect(remoteAddress)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) {
                        if (!future.isSuccess()) {
                            subscriber.onError(future.cause());
                        }
                    }
                })));
            if (LOG.isDebugEnabled()) {
                LOG.debug("createChannel and add codecs success for channel:{}/remoteAddress:{}",channel,remoteAddress);
            }
        } catch (Throwable e) {
            if (null!=channel) {
                channel.close();
            }
            throw e;
        }
    }
    */

    private static final Object OK = new Object();
    private static final AttributeKey<Object> VALID = AttributeKey.valueOf("__ISVALID");
    
    private static void markChannelValid(final Channel channel) {
        channel.attr(VALID).set(OK);
    }

    private static boolean isChannelValid(final Channel channel) {
        return null != channel.attr(VALID).get();
    }
    
    private Subscription channelClosure(
            final SocketAddress remoteAddress,
            final Channel channel, 
            final HandlersClosure handlersClosure) {
        final AtomicBoolean isUnsubscribed = new AtomicBoolean(false);
        return new Subscription() {
            @Override
            public void unsubscribe() {
                if (isUnsubscribed.compareAndSet(false, true)) {
                    try {
                        handlersClosure.close();
                    } catch (IOException e) {
                    }
                    if (!channel.isActive() || !isChannelValid(channel)
                        || !_channelPool.recycleChannel(remoteAddress, channel)) {
                        channel.close();
                    }
                }
            }
            @Override
            public boolean isUnsubscribed() {
                return isUnsubscribed.get();
            }};
    }

    public DefaultHttpClient(final OutboundFeature.Applicable... defaultFeatures) {
        this(1, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(new DefaultChannelPool(), 
            new AbstractChannelCreator() {
                @Override
                protected void initializeBootstrap(final Bootstrap bootstrap) {
                    bootstrap
                    .group(new NioEventLoopGroup(processThreadNumber))
                    .channel(NioSocketChannel.class);
                }},
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(),
            new AbstractChannelCreator() {
                @Override
                protected void initializeBootstrap(final Bootstrap bootstrap) {
                    bootstrap.group(eventLoopGroup).channel(channelType);
                }},
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(),
            new AbstractChannelCreator() {
                @Override
                protected void initializeBootstrap(final Bootstrap bootstrap) {
                    bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
                }},
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(new DefaultChannelPool(), channelCreator, defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final ChannelCreator channelCreator,
            final OutboundFeature.Applicable... defaultFeatures) {
        this._channelPool = channelPool;
        this._channelCreator = channelCreator;
        this._defaultFeatures = defaultFeatures;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }

    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final OutboundFeature.Applicable[] _defaultFeatures;
}
