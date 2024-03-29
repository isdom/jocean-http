/**
 *
 */
package org.jocean.http.client.internal;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.util.Feature2Handler;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.TrafficCounterAware;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpClient.class);

    public int getLowWaterMark() {
        return this._lowWaterMark;
    }

    public void setLowWaterMark(final int low) {
        this._lowWaterMark = low;
    }

    public int getHighWaterMark() {
        return this._highWaterMark;
    }

    public void setHighWaterMark(final int high) {
        this._highWaterMark = high;
    }

    public int getRecvBufSize() {
        return this._recvBufSize;
    }

    public void setRecvBufSize(final int recvBufSize) {
        this._recvBufSize = recvBufSize;
    }

    public int getSendBufSize() {
        return this._sendBufSize;
    }

    public void setSendBufSize(final int sendBufSize) {
        this._sendBufSize = sendBufSize;
    }

    @Override
    public InitiatorBuilder initiator() {
        final AtomicReference<Func0<SocketAddress>> _remoteAddressProvider
        = new AtomicReference<>();
    final List<Feature> _features = new ArrayList<>();

    return new InitiatorBuilder() {
        @Override
        public InitiatorBuilder remoteAddress(
                final SocketAddress remoteAddress) {
            _remoteAddressProvider.set(new Func0<SocketAddress>() {
                @Override
                public SocketAddress call() {
                    return remoteAddress;
                }});
            return this;
        }

        @Override
        public InitiatorBuilder remoteAddress(final Func0<SocketAddress> remoteAddressProvider) {
            _remoteAddressProvider.set(remoteAddressProvider);
            return this;
        }

        @Override
        public InitiatorBuilder feature(final Feature... features) {
            for (final Feature f : features) {
                if (null != f) {
                    _features.add(f);
                }
            }
            return this;
        }

        @Override
        public Observable<? extends HttpInitiator> build() {
            if (null == _remoteAddressProvider.get()) {
                throw new RuntimeException("remoteAddress not set");
            }
            return initiator0(_remoteAddressProvider.get(), _features.toArray(Feature.EMPTY_FEATURES));
        }};
    }

    private final Action1<HttpInitiator> ON_INITIATOR = new Action1<HttpInitiator>() {
        @Override
        public void call(final HttpInitiator initiator) {
            _initiatorObserver.foreachComponent(new Action1<Subscriber<? super HttpInitiator>>() {
                @Override
                public void call(final Subscriber<? super HttpInitiator> subscriber) {
                    subscriber.onNext(initiator);
                }});
        }};

    private Observable<? extends HttpInitiator> initiator0(
            final Func0<SocketAddress> remoteAddressProvider,
            final Feature... features) {
        final Feature[] allfeatures = cloneFeatures(Feature.Util.union(this._defaultFeatures, features));
        return this._channelPool.retainChannel(remoteAddressProvider)
            .onErrorResumeNext(createChannelAndConnectTo(remoteAddressProvider, allfeatures))
            .doOnNext(fillChannelAware(allfeatures))
            .map(channel2initiator(allfeatures))
            .doOnNext(ON_INITIATOR)
            ;
    }

    private Func1<Channel, HttpInitiator> channel2initiator(final Feature[] features) {
        return new Func1<Channel, HttpInitiator>() {
            @Override
            public HttpInitiator call(final Channel channel) {
                final DefaultHttpInitiator initiator = new DefaultHttpInitiator(channel);

                // enable recycle action
                initiator.doOnHalt(_RECYCLE_CHANNEL);
                //  set water mark's low/high
                if (_lowWaterMark >= 0
                    && _highWaterMark >= 0
                    && _highWaterMark >= _lowWaterMark) {
                    initiator.writeCtrl().setWriteBufferWaterMark(_lowWaterMark, _highWaterMark);
                }

                //  apply features per interaction
                Nettys.applyFeaturesToChannel(
                        initiator.onHalt(),
                        channel,
                        _FOR_INTERACTION,
                        features);

                //  enable TrafficCounter if needed
                final TrafficCounterAware trafficCounterAware =
                        InterfaceUtils.compositeIncludeType(
                            TrafficCounterAware.class,
                            (Object[])features);
                if (null!=trafficCounterAware) {
                    try {
                        trafficCounterAware.setTrafficCounter(initiator.traffic());
                    } catch (final Exception e) {
                        LOG.warn("exception when invoke setTrafficCounter for channel ({}), detail: {}",
                                channel, ExceptionUtils.exception2detail(e));
                    }
                }

                return initiator;
            }};
    }

    private static Action1<? super Channel> fillChannelAware(final Feature[] features) {
        final ChannelAware channelAware =
                InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);

        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("dump outbound channel({})'s config: \n{}", channel, Nettys.dumpChannelConfig(channel.config()));
                }
                if (null!=channelAware) {
                    try {
                        channelAware.setChannel(channel);
                    } catch (final Exception e) {
                        LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                                channel, ExceptionUtils.exception2detail(e));
                    }
                }
            }};
    }

    private Observable<? extends Channel> createChannelAndConnectTo(
            final Func0<SocketAddress> remoteAddressProvider,
            final Feature[] features) {
        return this._channelCreator.newChannel()
            .doOnNext(_SET_SEND_RECV_BUF_SIZE)
            .doOnNext(_DISABLE_AUTOREAD)
            .doOnNext(applyPerChannelFeatures(features))
            .flatMap(RxNettys.asyncConnectToMaybeSSL(remoteAddressProvider));
    }

    public static Action1<Channel> applyPerChannelFeatures(
            final Feature[] features) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                Nettys.applyFeaturesToChannel(null,
                    channel,
                    _FOR_CHANNEL,
                    features);
                Nettys.applyHandler(channel.pipeline(), HttpHandlers.HTTPCLIENT);
            }
        };
    }

    private static Feature[] cloneFeatures(final Feature[] features) {
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

    public DefaultHttpClient(final boolean pooled) {
        this(0, pooled, Feature.EMPTY_FEATURES);
    }

    public DefaultHttpClient(final int processThreadNumber) {
        this(processThreadNumber, true, Feature.EMPTY_FEATURES);
    }

    public DefaultHttpClient(final int processThreadNumber,
            final boolean pooled) {
        this(processThreadNumber, pooled, Feature.EMPTY_FEATURES);
    }

    public DefaultHttpClient() {
        this(0, true, Feature.EMPTY_FEATURES);
    }

    public DefaultHttpClient(final Feature... defaultFeatures) {
        this(0, true, defaultFeatures);
    }

    public DefaultHttpClient(final int processThreadNumber,
            final boolean pooled,
            final Feature... defaultFeatures) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            pooled ? new DefaultChannelPool(HttpHandlers.ON_CHANNEL_INACTIVE) : Nettys.unpoolChannels(),
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
            new DefaultChannelPool(HttpHandlers.ON_CHANNEL_INACTIVE),
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
            new DefaultChannelPool(HttpHandlers.ON_CHANNEL_INACTIVE),
            defaultFeatures);
    }

    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final Feature... defaultFeatures) {
        this(channelCreator, new DefaultChannelPool(HttpHandlers.ON_CHANNEL_INACTIVE), defaultFeatures);
    }

    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final ChannelPool channelPool,
            final Feature... defaultFeatures) {
        this._channelCreator = channelCreator;
        this._channelPool = channelPool;
        this._defaultFeatures = (null != defaultFeatures) ? defaultFeatures : Feature.EMPTY_FEATURES;
        this._RECYCLE_CHANNEL = initiator -> {
            final DefaultHttpInitiator defaultInitiator = (DefaultHttpInitiator)initiator;
            final Channel channel = defaultInitiator.channel();
            if (!defaultInitiator.inTransacting()
                && defaultInitiator.isKeepAlive()) {
                if (_channelPool.recycleChannel(channel)) {
                    // recycle success
                    // perform read for recv FIN SIG and to change state to close
                    channel.read();
                }
            } else {
                channel.close();
            }
        };
    }

    @Override
    public void close() {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }

    public Observable<HttpInitiator> onInitiator() {
        return Observable.unsafeCreate(new OnSubscribe<HttpInitiator>() {
            @Override
            public void call(final Subscriber<? super HttpInitiator> subscriber) {
                addInitiatorSubscriber(subscriber);
            }});
    }

    private void addInitiatorSubscriber(final Subscriber<? super HttpInitiator> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._initiatorObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(() -> _initiatorObserver.removeComponent(subscriber)));
        }
    }

    private int _lowWaterMark = -1;
    private int _highWaterMark = -1;
    private int _sendBufSize = -1;
    private int _recvBufSize = -1;

    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;

    static final Feature2Handler _FOR_INTERACTION;

    static final Feature2Handler _FOR_CHANNEL;

    private final COWCompositeSupport<Subscriber<? super HttpInitiator>> _initiatorObserver = new COWCompositeSupport<>();

    private final Action1<HttpInitiator> _RECYCLE_CHANNEL;

    private final Action1<Channel> _SET_SEND_RECV_BUF_SIZE = channel -> {
            if (_sendBufSize > 0) {
                LOG.debug("channel({})'s default SO_SNDBUF is {} bytes, and will be reset to {} bytes",
                        channel,
                        channel.config().getOption(ChannelOption.SO_SNDBUF),
                        _sendBufSize);
                channel.config().setOption(ChannelOption.SO_SNDBUF, _sendBufSize);
            }
            if (_recvBufSize > 0) {
                LOG.debug("channel({})'s default SO_RCVBUF is {} bytes, and will be reset to {} bytes",
                        channel,
                        channel.config().getOption(ChannelOption.SO_RCVBUF),
                        _recvBufSize);
                channel.config().setOption(ChannelOption.SO_RCVBUF, _recvBufSize);
            }
        };

    private static final Action1<Channel> _DISABLE_AUTOREAD = channel -> channel.config().setAutoRead(false);

    static {
        _FOR_INTERACTION = new Feature2Handler();
        _FOR_INTERACTION.register(Feature.ENABLE_LOGGING.getClass(), HttpHandlers.LOGGING);
        _FOR_INTERACTION.register(Feature.ENABLE_LOGGING_OVER_SSL.getClass(), HttpHandlers.LOGGING_OVER_SSL);
        _FOR_INTERACTION.register(Feature.ENABLE_COMPRESSOR.getClass(), HttpHandlers.CONTENT_DECOMPRESSOR);
        _FOR_INTERACTION.register(Feature.ENABLE_CLOSE_ON_IDLE.class, HttpHandlers.CLOSE_ON_IDLE);
        _FOR_INTERACTION.register(Feature.ENABLE_MULTIPART.getClass(), HttpHandlers.CHUNKED_WRITER);

        _FOR_CHANNEL = new Feature2Handler();
        _FOR_CHANNEL.register(Feature.ENABLE_SSL.class, HttpHandlers.SSL);
    }
}
