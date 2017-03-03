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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.mbean.TradeHolderMXBean;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys;
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
    
    public int getInboundBlockSize() {
        return this._inboundBlockSize;
    }

    public void setInboundBlockSize(final int inboundBlockSize) {
        this._inboundBlockSize = inboundBlockSize;
    }

    public int getInboundRecvBufSize() {
        return this._inboundRecvBufSize;
    }

    public void setInboundRecvBufSize(final int inboundRecvBufSize) {
        this._inboundRecvBufSize = inboundRecvBufSize;
    }
    
    @Override
    public int getCurrentInboundMemoryInBytes() {
        return this._currentInboundMemory.get();
    }
    
    @Override
    public int getPeakInboundMemoryInBytes() {
        return this._peakInboundMemory.get();
    }
    
    @Override
    public float getCurrentInboundMemoryInMBs() {
        return getCurrentInboundMemoryInBytes() / (float)(1024 * 1024);
    }
    
    @Override
    public float getPeakInboundMemoryInMBs() {
        return getPeakInboundMemoryInBytes() / (float)(1024 * 1024);
    }
    
    @Override
    public int getNumActiveTrades() {
        return this._trades.size();
    }

    @Override
    public long getNumStartedTrades() {
        return this._numStartedTrades.get();
    }
    
    @Override
    public long getNumCompletedTrades() {
        return this._numCompletedTrades.get();
    }
    
    @Override
    public String[] getAllActiveTrade() {
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
    
    @SuppressWarnings("deprecation")
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder,
            final Feature... features) {
        return Observable.create(new Observable.OnSubscribe<HttpTrade>() {
            @Override
            public void call(final Subscriber<? super HttpTrade> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    final List<Channel> awaitChannels = new CopyOnWriteArrayList<>();
                    bootstrap.childHandler(new Initializer() {
                        @Override
                        protected void initChannel(final Channel channel) throws Exception {
                            channel.config().setAutoRead(false);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("dump inbound channel({})'s config: \n{}", 
                                        channel, Nettys.dumpChannelConfig(channel.config()));
                            }
                            if ( _inboundRecvBufSize > 0) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("inbound channel({})'s default SO_RCVBUF is {} bytes, and will be reset to {} bytes",
                                            channel, 
                                            channel.config().getOption(ChannelOption.SO_RCVBUF), 
                                            _inboundRecvBufSize);
                                }
                                channel.config().setOption(ChannelOption.SO_RCVBUF, _inboundRecvBufSize);
                            }
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
                    try {
                        future.sync();
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
                        final ServerChannelAware serverChannelAware = serverChannelAwareOf(features);
                        if (null != serverChannelAware) {
                            serverChannelAware.setServerChannel((ServerChannel)future.channel());
                        }
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                }
            }})
            ;
    }

    private void awaitInboundRequest(
            final Channel channel,
            final Subscriber<? super HttpTrade> subscriber, 
            final List<Channel> awaitChannels) {
        awaitChannels.add(channel);
        channel.read();
        APPLY.ON_CHANNEL_READ.applyTo(channel.pipeline(), 
            new Action0() {
                @Override
                public void call() {
                    awaitChannels.remove(channel);
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onNext(
                            addToTrades(httpTradeOf(channel, 
                                doRecycleChannel(channel, subscriber, awaitChannels),
                                new Action1<HttpTrade>() {
                                    @Override
                                    public void call(HttpTrade t) {
                                        _numCompletedTrades.incrementAndGet();
                                    }}
                                ))
                            )
                        ;
                    } else {
                        LOG.warn("HttpTrade Subscriber {} has unsubscribed, so close channel({})",
                                subscriber, channel);
                        channel.close();
                    }
                }});
    }

    private void updateCurrentInboundMemory(final int delta) {
        final int current = this._currentInboundMemory.addAndGet(delta);
        if (delta > 0) {
            boolean updated = false;
            
            do {
                // try to update peak memory value
                final int peak = this._peakInboundMemory.get();
                if (current > peak) {
                    updated = this._peakInboundMemory.compareAndSet(peak, current);
                } else {
                    break;
                }
            } while (!updated);
        }
    }
    
    @SafeVarargs
    private final HttpTrade httpTradeOf(final Channel channel, 
        final Action1<HttpTrade> ... onTerminates) {
        this._numStartedTrades.incrementAndGet();
        final DefaultHttpTrade trade = new DefaultHttpTrade(channel, onTerminates);
        final AtomicInteger _lastAddedSize = new AtomicInteger(0);
        
        trade.inbound().message().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(final HttpObject msg) {
                final int currentsize = trade.inbound().holdingMemorySize();
                final int lastsize = _lastAddedSize.getAndSet(currentsize);
                if (lastsize >= 0) { // -1 means trade has closed
                    updateCurrentInboundMemory(currentsize - lastsize);
                } else {
                    //  TODO? set lastsize (== -1) back to _lastAddedSize ?
                }
            }});
        trade.doOnTerminate(new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade t) {
                updateCurrentInboundMemory(-_lastAddedSize.getAndSet(-1));
            }});
        return trade;
    }
    
    private Action1<HttpTrade> doRecycleChannel(
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
                    && ((DefaultHttpTrade)trade).isEndedWithKeepAlive()
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
    
    private int _inboundRecvBufSize = -1;
    private int _inboundBlockSize = 0;
    
    private static final Class2ApplyBuilder _APPLY_BUILDER;
    
    private final AtomicLong     _numStartedTrades = new AtomicLong(0);
    private final AtomicLong     _numCompletedTrades = new AtomicLong(0);
    private final Set<HttpTrade> _trades = new ConcurrentSkipListSet<HttpTrade>();
    private final AtomicInteger  _currentInboundMemory = new AtomicInteger(0);
    private final AtomicInteger  _peakInboundMemory = new AtomicInteger(0);
        
    static {
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), APPLY.LOGGING_OVER_SSL);
        _APPLY_BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_COMPRESSOR);
        _APPLY_BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
    }
}
