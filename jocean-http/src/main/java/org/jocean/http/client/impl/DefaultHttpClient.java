/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
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
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

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
                markChannelConnected(ctx.channel());
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
                    markChannelConnected(ctx.channel());
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
    public Observable<? extends HttpObject> sendRequest(
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

    private Func1<ChannelHandler, Observable<? extends Channel>> createChannelObservable(
            final SocketAddress remoteAddress, 
            final OutboundFeature.Applicable[] features) {
        return new Func1<ChannelHandler, Observable<? extends Channel>> () {
            @Override
            public Observable<? extends Channel> call(final ChannelHandler workHandler) {
//                return _channelPool.retainChannel(remoteAddress);
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        _channelPool.retainChannel(remoteAddress)
                        .subscribe(
                            new Action1<Channel>() {
                                @Override
                                public void call(final Channel channel) {
                                    final HandlersClosure handlersClosure = 
                                            Nettys.channelHandlersClosure(channel);
                                    for (OutboundFeature.Applicable applicable : features) {
                                        if (applicable.isRemovable()) {
                                            handlersClosure.call(applicable.call(channel));
                                        }
                                    }
                                    
                                    handlersClosure.call(
                                        Nettys.insertHandler(
                                            channel.pipeline(),
                                            WORK_HANDLER,
                                            workHandler,
                                            OutboundFeature.TO_ORDINAL)
                                        );
                                    
                                    subscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
                                    
                                    if (isChannelConnected(channel)) {
                                        subscriber.onNext(channel);
                                        subscriber.onCompleted();
                                    } else {
                                        startToConnect(channel, 
                                                remoteAddress,
                                                features, 
                                                subscriber,
                                                handlersClosure);
                                    }
                                }}, 
                            new Action1<Throwable>() {
                                @Override
                                public void call(Throwable e) {
                                    subscriber.onError(e);
                                }});
                }});
            }};
    }

    private void startToConnect(
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
        
        OutboundFeature.HTTPCLIENT_CODEC.applyTo(channel);
        
        handlersClosure.call(
            Nettys.insertHandler(
                channel.pipeline(),
                "completeOrErrorNotifier",
                new CompleteOrErrorNotifier(subscriber, 
                    OutboundFeature.isSSLEnabled(channel.pipeline())),
                OutboundFeature.TO_ORDINAL)
        );
        
        RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
            .call(channel.connect(remoteAddress))
            .subscribe(subscriber);
        /*
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
        */
        if (LOG.isDebugEnabled()) {
            LOG.debug("createChannel and add codecs success for channel:{}/remoteAddress:{}",channel,remoteAddress);
        }
    }

    private static final Object OK = new Object();
    private static final AttributeKey<Object> CONNECTED = AttributeKey.valueOf("__CONNECTED");
    
    private static void markChannelConnected(final Channel channel) {
        channel.attr(CONNECTED).set(OK);
    }

    private static boolean isChannelConnected(final Channel channel) {
        return null != channel.attr(CONNECTED).get();
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
                    if (!channel.isActive() || !isChannelConnected(channel)
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
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }}), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }}),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }}),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(new DefaultChannelPool(channelCreator), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final OutboundFeature.Applicable... defaultFeatures) {
        this._channelPool = channelPool;
        this._defaultFeatures = defaultFeatures;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        //  TODO
//        this._channelCreator.close();
    }

    private final ChannelPool _channelPool;
    private final OutboundFeature.Applicable[] _defaultFeatures;
}
