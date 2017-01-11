/**
 * 
 */
package org.jocean.http.server.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.mbean.TradeHolderMXBean;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServerBuilder implements HttpServerBuilder, TradeHolderMXBean {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpServerBuilder.class);
    
    @Override
    public int getTradeCount() {
        return this._trades.size();
    }

    @Override
    public String[] getAllTrade() {
        final List<String> infos = new ArrayList<>();
        for (HttpTrade t : this._trades) {
            infos.add(t.toString());
        }
        return infos.toArray(new String[0]);
    }
    
    private HttpTrade addToTrades(final HttpTrade trade) {
        this._trades.add(trade);
        return trade;
    }
    
    private void removeFromTrades(final HttpTrade trade) {
        final boolean deleted = this._trades.remove(trade);
        if (deleted) {
//            LOG.debug("trade{} has been erased.", trade);
        }
    }
    
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
        return Observable.create(new Observable.OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<? super Object> subscriber) {
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
                                try {
                                    awaitChannels.remove(0).close();
                                } catch (Exception e) {
                                    LOG.warn("exception when remove all awaitChannels, detail: {}",
                                            ExceptionUtils.exception2detail(e));
                                }
                            }
                        }}));
                    subscriber.onNext(future);
                }
            }})
            .flatMap(new Func1<Object, Observable<? extends Object>>() {
                @Override
                public Observable<? extends Object> call(final Object obj) {
                    if (obj instanceof ChannelFuture) {
                        return RxNettys.channelObservableFromFuture((ChannelFuture)obj);
                    } else {
                        return Observable.just(obj);
                    }
                }})
            .flatMap(new Func1<Object, Observable<HttpTrade>>() {
                @Override
                public Observable<HttpTrade> call(final Object obj) {
                    if (obj instanceof ServerChannel) {
                        final ServerChannelAware serverChannelAware = serverChannelAwareOf(features);
                        if (null != serverChannelAware) {
                            serverChannelAware.setServerChannel((ServerChannel)obj);
                        }
                        return Observable.<HttpTrade>empty();
                    } else if (obj instanceof HttpTrade) {
                        return Observable.<HttpTrade>just((HttpTrade)obj);
                    } else {
                        return Observable.<HttpTrade>error(new RuntimeException("unknowm obj:" + obj));
                    }
                }})
            ;
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
                            addToTrades(httpTradeOf(channel))
                            .addCloseHook(actionRecycleChannel(channel, subscriber, awaitChannels)));
                    } else {
                        LOG.warn("HttpTrade Subscriber {} has unsubscribed, so close channel({})",
                                subscriber, channel);
                        channel.close();
                    }
                }});
    }

    private HttpTrade httpTradeOf(final Channel channel) {
        return new DefaultHttpTrade(channel, httpobjObservable(channel));
    }
    
    private static Observable<? extends HttpObject> httpobjObservable(final Channel channel) {
        return Observable.create(new Observable.OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    if (null != channel.pipeline().get(APPLY.HTTPOBJ_SUBSCRIBER.name()) ) {
                        // already add HTTPOBJ_SUBSCRIBER Handler, so throw exception
                        LOG.warn("channel ({}) already add HTTPOBJ_SUBSCRIBER handler, internal error",
                                channel);
                        throw new RuntimeException("Channel already add HTTPOBJ_SUBSCRIBER handler.");
                    }
                    RxNettys.installDoOnUnsubscribe(channel, 
                            DoOnUnsubscribe.Util.from(subscriber));
                    subscriber.add(Subscriptions.create(
                        RxNettys.actionToRemoveHandler(channel, 
                            APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber))));
                } else {
                    LOG.warn("subscriber {} isUnsubscribed, can't used as HTTPOBJ_SUBSCRIBER ", subscriber);
                }
            }} );
    }
    
    private Action1<HttpTrade> actionRecycleChannel(
            final Channel channel,
            final Subscriber<? super HttpTrade> subscriber, 
            final List<Channel> awaitChannels) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                removeFromTrades(trade);
                RxNettys.installDoOnUnsubscribe(channel, 
                        DoOnUnsubscribe.Util.UNSUBSCRIBE_NOW);
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
    
    private static final Class2ApplyBuilder _APPLY_BUILDER;
    
    private final Set<HttpTrade> _trades = new ConcurrentSkipListSet<HttpTrade>();
        
    static {
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), APPLY.LOGGING_OVER_SSL);
        _APPLY_BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_COMPRESSOR);
        _APPLY_BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
    }
}
