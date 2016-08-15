/**
 * 
 */
package org.jocean.http.server.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServerBuilder implements HttpServerBuilder {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpServerBuilder.class);
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder) {
        return defineServer(localAddress, featuresBuilder, (Feature[])null);
    }
    
    @Override
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Feature... features) {
        return defineServer(localAddress, null, features);
    }
    
    private static abstract class Initializer extends ChannelInitializer<Channel> implements Ordered {
        @Override
        public String toString() {
            return "[DefaultHttpServer' ChannelInitializer]";
        }
        @Override
        public int ordinal() {
            return -1000;
        }
    }
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder,
            final Feature... features) {
        return Observable.create(new OnSubscribe<HttpTrade>() {
            @Override
            public void call(final Subscriber<? super HttpTrade> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    final List<Channel> awaitChannels = new CopyOnWriteArrayList<>();
                    bootstrap.childHandler(new Initializer() {
                        @Override
                        protected void initChannel(final Channel channel) throws Exception {
                            final Feature[] actualFeatures = JOArrays.addFirst(Feature[].class, 
                                    featuresOf(featuresBuilder), features);
                            final Feature[] applyFeatures = 
                                    (null != actualFeatures && actualFeatures.length > 0 ) ? actualFeatures : _defaultFeatures;
                            for (Feature feature : applyFeatures) {
                                if (feature instanceof FeatureOverChannelHandler) {
                                    ((FeatureOverChannelHandler)feature).call(_APPLY_BUILDER, channel.pipeline());
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("initChannel with feature:{}", feature);
                                    }
                                }
                            }
                            APPLY.HTTPSERVER.applyTo(channel.pipeline());
                            awaitInboundRequest(channel, subscriber, awaitChannels);
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    subscriber.add(Subscriptions.from(future));
                    subscriber.add(RxNettys.subscriptionForCloseChannel(future.channel()));
                    subscriber.add(Subscriptions.create(new Action0() {
                        @Override
                        public void call() {
                            while (!awaitChannels.isEmpty()) {
                                awaitChannels.remove(0).close();
                            }
                        }}));
                    future.addListener(RxNettys.listenerOfOnError(subscriber))
                        .addListener(RxNettys.listenerOfSetServerChannel(serverChannelAwareOf(features)));
                }
            }});
    }

    private void awaitInboundRequest(
            final Channel channel,
            final Subscriber<? super HttpTrade> subscriber, 
            final List<Channel> awaitChannels) {
        awaitChannels.add(channel);
        APPLY.ON_CHANNEL_READ.applyTo(channel.pipeline(), 
            new Action0() {
                @Override
                public void call() {
                    awaitChannels.remove(channel);
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onNext(
                            httpTradeOf(channel)
                            .doOnClosed(actionRecycleChannel(channel, subscriber, awaitChannels)));
                    } else {
                        LOG.warn("HttpTrade Subscriber {} has unsubscribed, so close channel({})",
                                subscriber, channel);
                        channel.close();
                    }
                }});
    }

    private HttpTrade httpTradeOf(final Channel channel) {
        return new DefaultHttpTrade(channel, RxNettys.httpobjObservable(channel));
    }
    
    private Action1<HttpTrade> actionRecycleChannel(
            final Channel channel,
            final Subscriber<? super HttpTrade> subscriber, 
            final List<Channel> awaitChannels) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                if (channel.isActive()
                    && trade.isEndedWithKeepAlive()
                    && !subscriber.isUnsubscribed()) {
                    channel.flush();
                    awaitInboundRequest(channel, subscriber, awaitChannels);
                } else {
                    //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
                    //  Detail:
                    //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
                    //
                    //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    //
                    // See https://github.com/netty/netty/issues/2983 for more information.
                    channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
                }
            }};
    }
    
    public DefaultHttpServerBuilder() {
        this(1, 0, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpServerBuilder(
            final int processThreadNumberForAccept, 
            final int processThreadNumberForWork
            ) {
        this(processThreadNumberForAccept, processThreadNumberForWork, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpServerBuilder(
            final int processThreadNumberForAccept, 
            final int processThreadNumberForWork,
            final Feature... defaultFeatures) {
        this(new AbstractBootstrapCreator(
                new NioEventLoopGroup(processThreadNumberForAccept), 
                new NioEventLoopGroup(processThreadNumberForWork)) {
            @Override
            protected void initializeBootstrap(ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(NioServerSocketChannel.class);
            }}, defaultFeatures);
    }
    
    public DefaultHttpServerBuilder(
            final BootstrapCreator creator,
            final Feature... defaultFeatures) {
        this._creator = creator;
        this._defaultFeatures = null!=defaultFeatures ? defaultFeatures : Feature.EMPTY_FEATURES;
    }

    @Override
    public void close() throws IOException {
        this._creator.close();
    }
    
    private static Feature[] featuresOf(final Func0<Feature[]> featuresBuilder) {
        return null != featuresBuilder ? featuresBuilder.call() : null;
    }
    
    private static ServerChannelAware serverChannelAwareOf(
            final Feature[] applyFeatures) {
        final ServerChannelAware serverChannelAware = 
                InterfaceUtils.compositeIncludeType(ServerChannelAware.class, 
                    (Object[])applyFeatures);
        return serverChannelAware;
    }

    private final BootstrapCreator _creator;
    private final Feature[] _defaultFeatures;
//    private final boolean _cacheRequest;
    
    private static final Class2ApplyBuilder _APPLY_BUILDER;
        
    static {
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_COMPRESSOR);
        _APPLY_BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
        
    }
}
