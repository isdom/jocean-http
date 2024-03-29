/**
 *
 */
package org.jocean.http.server.internal;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.server.HttpServerBuilder;
import org.jocean.http.server.mbean.HttpServerMXBean;
import org.jocean.http.util.Feature2Handler;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.AwaitChannelsAware;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServerBuilder implements HttpServerBuilder, MBeanRegisterAware {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpServerBuilder.class);

    public void setMbeanSuffix(final String mbeanSuffix) {
        this._mbeanSuffix = mbeanSuffix;
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
        this._register.registerMBean(this._mbeanSuffix, new HttpServerMXBean() {

            @Override
            public int getAcceptThreadCount() {
                return _acceptThreadCount;
            }

            @Override
            public int getWorkThreadCount() {
                return _workThreadCount;
            }

            @Override
            public int getCurrentInboundMemoryInBytes() {
                return _currentInboundMemory.get();
            }

            @Override
            public int getPeakInboundMemoryInBytes() {
                return _peakInboundMemory.get();
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
                return _trades.size();
            }

            @Override
            public long getNumStartedTrades() {
                return _numStartedTrades.get();
            }

            @Override
            public long getNumCompletedTrades() {
                return _numCompletedTrades.get();
            }

            @Override
            public String[] getAllActiveTrade() {
                final List<String> infos = new ArrayList<>();
                for (final HttpTrade t : _trades) {
                    infos.add(t.toString());
                }
                return infos.toArray(new String[0]);
            }});
    }

    public int getInboundRecvBufSize() {
        return this._inboundRecvBufSize;
    }

    public void setInboundRecvBufSize(final int inboundRecvBufSize) {
        this._inboundRecvBufSize = inboundRecvBufSize;
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

    @Override
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

    @Override
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Func0<Feature[]> featuresBuilder,
            final Feature... features) {
        return Observable.unsafeCreate(new OnSubscribe<HttpTrade>() {
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
                                    LOG.debug("channel({})'s default SO_RCVBUF is {} bytes, and will be reset to {} bytes",
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
                            for (final Feature feature : applyFeatures) {
                                if (feature instanceof FeatureOverChannelHandler) {
                                    ((FeatureOverChannelHandler)feature).call(_APPLY_BUILDER, channel.pipeline());
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("initChannel with feature:{}", feature);
                                    }
                                }
                            }
                            Nettys.applyHandler(channel.pipeline(), HttpHandlers.HTTPSERVER);
                            awaitInboundRequest(channel, subscriber, awaitChannels);
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    try {
                        future.sync();
                        subscriber.add(RxNettys.subscriptionForCloseChannel(future.channel()));
                        subscriber.add(Subscriptions.create(() -> {
                                while (!awaitChannels.isEmpty()) {
                                    try {
                                        awaitChannels.remove(0).close();
                                    } catch (final Exception e) {
                                        LOG.warn("exception when remove all awaitChannels, detail: {}",
                                                ExceptionUtils.exception2detail(e));
                                    }
                                }
                            }));
                        if (null != features) {
                            final ServerChannelAware serverChannelAware = serverChannelAwareOf(features);
                            if (null != serverChannelAware) {
                                serverChannelAware.setServerChannel((ServerChannel)future.channel());
                            }
                            final AwaitChannelsAware awaitChannelsAware = awaitChannelsAwareOf(features);
                            if (null != awaitChannelsAware) {
                                awaitChannelsAware.setAwaitChannels(awaitChannels);
                            }

                        }
                    } catch (final Exception e) {
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

        final Subscription timout4channel = Observable.timer(30, TimeUnit.SECONDS).subscribe( any -> {
            awaitChannels.remove(channel);
            channel.close();
            LOG.info("Channel({}) no any inbound for 30s, remove from awaitChannels and close it.", channel);
        });

        // TBD: workround to remove channel from awaitChannels when channel inactive
        //      and when channel receive normal traffic then disable this action, bcs trade will deal with inactive state
        final AtomicReference<Action0> terminateRef = new AtomicReference<>();
        Nettys.applyToChannel(terminateAction -> terminateRef.set(terminateAction),
                channel,
                HttpHandlers.ON_CHANNEL_INACTIVE,
                (Action0)() -> {
                    timout4channel.unsubscribe();
                    LOG.info("Channel({}) inactived, remove from awaitChannels.", channel);
                    awaitChannels.remove(channel);
                });

        Nettys.applyHandler(channel.pipeline(), HttpHandlers.ON_CHANNEL_READ,
            (Action0)() -> {
                    timout4channel.unsubscribe();
                    if (terminateRef.get() != null) {
                        terminateRef.get().call();
                    }
                    awaitChannels.remove(channel);
                    if (!subscriber.isUnsubscribed()) {
                        final DefaultHttpTrade trade = httpTradeOf(channel,
                                doRecycleChannel(channel, subscriber, awaitChannels),
                                t -> _numCompletedTrades.incrementAndGet());
                        trade.received().first().subscribe(any -> {
                            if (trade.isActive()) {
                                subscriber.onNext(trade);
                            } else {
                                LOG.info("HttpTrade({}) has unactived, so ignore.", trade);
                            }
                        });
                    } else {
                        LOG.warn("HttpTrade Subscriber {} has unsubscribed, so close channel({})",
                                subscriber, channel);
                        channel.close();
                    }
                });
        channel.read();
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
    private final DefaultHttpTrade httpTradeOf(final Channel channel, final Action1<HttpTrade> ... onHalts) {
        this._numStartedTrades.incrementAndGet();
        final DefaultHttpTrade trade = new DefaultHttpTrade(channel);

        addToTrades(trade);
        for (final Action1<HttpTrade> onhalt : onHalts) {
            trade.doOnHalt(onhalt);
        }

        final AtomicInteger _lastAddedSize = new AtomicInteger(0);

        /* TBD FIX TODO
        trade.inbound().subscribe(new Action1<HttpObject>() {
            @Override
            public void call(final HttpObject msg) {
                final int currentsize = trade.inboundHolder().retainedByteBufSize();
                final int lastsize = _lastAddedSize.getAndSet(currentsize);
                if (lastsize >= 0) { // -1 means trade has closed
                    updateCurrentInboundMemory(currentsize - lastsize);
                } else {
                    //  TODO? set lastsize (== -1) back to _lastAddedSize ?
                }
            }});
            */
        trade.doOnHalt( t -> updateCurrentInboundMemory(-_lastAddedSize.getAndSet(-1)));
        return trade;
    }

    private Action1<HttpTrade> doRecycleChannel(
            final Channel channel,
            final Subscriber<? super HttpTrade> subscriber,
            final List<Channel> awaitChannels) {
        return trade -> {
                removeFromTrades(trade);
                final DefaultHttpTrade defaultHttpTrade = (DefaultHttpTrade)trade;
                if (channel.isActive()
                    && !defaultHttpTrade.inTransacting()
                    && defaultHttpTrade.isKeepAlive()
                    && !subscriber.isUnsubscribed()) {
                    awaitInboundRequest(channel, subscriber, awaitChannels);
                } else {
                    channel.close();
                }
            };
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
            protected void initializeBootstrap(final ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(NioServerSocketChannel.class);
            }}, defaultFeatures);
        this._acceptThreadCount = processThreadNumberForAccept;
        this._workThreadCount = processThreadNumberForWork;
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

    private static ServerChannelAware serverChannelAwareOf(final Feature[] applyFeatures) {
        return InterfaceUtils.compositeIncludeType(ServerChannelAware.class, (Object[])applyFeatures);
    }

    private static AwaitChannelsAware awaitChannelsAwareOf(final Feature[] applyFeatures) {
        return InterfaceUtils.compositeIncludeType(AwaitChannelsAware.class, (Object[])applyFeatures);
    }

    private final BootstrapCreator _creator;
    private final Feature[] _defaultFeatures;
    private MBeanRegister _register;

    private int _inboundRecvBufSize = -1;
    private String _mbeanSuffix;

    private static final Feature2Handler _APPLY_BUILDER;

    private final AtomicLong     _numStartedTrades = new AtomicLong(0);
    private final AtomicLong     _numCompletedTrades = new AtomicLong(0);
    private final Set<HttpTrade> _trades = new ConcurrentSkipListSet<HttpTrade>();
    private final AtomicInteger  _currentInboundMemory = new AtomicInteger(0);
    private final AtomicInteger  _peakInboundMemory = new AtomicInteger(0);
    private int _acceptThreadCount = 0;
    private int _workThreadCount = 0;

    static {
        _APPLY_BUILDER = new Feature2Handler();
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING.getClass(), HttpHandlers.LOGGING);
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), HttpHandlers.LOGGING_OVER_SSL);
        _APPLY_BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), HttpHandlers.CONTENT_COMPRESSOR);
        _APPLY_BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, HttpHandlers.CLOSE_ON_IDLE);
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, HttpHandlers.SSL);
    }
}
