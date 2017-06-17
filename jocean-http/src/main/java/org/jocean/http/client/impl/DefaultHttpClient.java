/**
 * 
 */
package org.jocean.http.client.impl;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.client.HttpClient;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.TrafficCounterAware;
import org.jocean.http.util.TrafficCounterHandler;
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
import rx.functions.Action1;
import rx.functions.Func1;

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
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClient.class);
    
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
    
    public int getSendBufSize() {
        return this._sendBufSize;
    }

    public void setSendBufSize(final int sendBufSize) {
        this._sendBufSize = sendBufSize;
    }
    
    private final Action1<HttpInitiator> _RECYCLE_CHANNEL = new Action1<HttpInitiator>() {
        @Override
        public void call(final HttpInitiator initiator) {
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
        }};
        
    private final Action1<Channel> _SET_SNDBUF_SIZE = new Action1<Channel>() {
        @Override
        public void call(final Channel channel) {
            if ( _sendBufSize > 0) {
                if (LOG.isInfoEnabled()) {
                    LOG.info("channel({})'s default SO_SNDBUF is {} bytes, and will be reset to {} bytes",
                            channel, 
                            channel.config().getOption(ChannelOption.SO_SNDBUF), 
                            _sendBufSize);
                }
                channel.config().setOption(ChannelOption.SO_SNDBUF, _sendBufSize);
            }
        }};
        
    private static final Action1<Channel> _DISABLE_AUTOREAD = new Action1<Channel>() {
        @Override
        public void call(final Channel channel) {
            // disable autoRead
            channel.config().setAutoRead(false);
        }};
        
    @Override
    public InitiatorBuilder initiator() {
        final AtomicReference<SocketAddress> _remoteAddress 
        = new AtomicReference<>();
    final List<Feature> _features = new ArrayList<>();
    
    return new InitiatorBuilder() {
        @Override
        public InitiatorBuilder remoteAddress(
                final SocketAddress remoteAddress) {
            _remoteAddress.set(remoteAddress);
            return this;
        }

        @Override
        public InitiatorBuilder feature(final Feature... features) {
            for (Feature f : features) {
                if (null != f) {
                    _features.add(f);
                }
            }
            return this;
        }

        @Override
        public Observable<? extends HttpInitiator> build() {
            if (null == _remoteAddress.get()) {
                throw new RuntimeException("remoteAddress not set");
            }
            return initiator0(_remoteAddress.get(), 
                    _features.toArray(Feature.EMPTY_FEATURES));
        }};
    }
    
    private Observable<? extends HttpInitiator> initiator0(
            final SocketAddress remoteAddress,
            final Feature... features) {
        final Feature[] allfeatures = cloneFeatures(Feature.Util.union(this._defaultFeatures, features));
        return this._channelPool.retainChannel(remoteAddress)
            .onErrorResumeNext(createChannelAndConnectTo(remoteAddress, allfeatures))
            .doOnNext(fillChannelAware(allfeatures))
            .map(channel2initiator(allfeatures));
    }

    private Func1<Channel, HttpInitiator> channel2initiator(final Feature[] features) {
        return new Func1<Channel, HttpInitiator>() {
            @Override
            public HttpInitiator call(final Channel channel) {
                final DefaultHttpInitiator initiator = new DefaultHttpInitiator(channel);
                
                // enable recycle action
                initiator.doOnTerminate(_RECYCLE_CHANNEL);
//                    initiator.inbound().messageHolder().setMaxBlockSize(_inboundBlockSize);
                
                //  set water mark's low/high
                if (_lowWaterMark >= 0 
                    && _highWaterMark >= 0
                    && _highWaterMark >= _lowWaterMark) {
                    initiator.setWriteBufferWaterMark(_lowWaterMark, _highWaterMark);
                }
                
                //  apply features per interaction
                Nettys.applyFeaturesToChannel(
                        initiator.onTerminate(), 
                        channel, 
                        HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, 
                        features);
                
                //  enable TrafficCounter if needed
                final TrafficCounterAware trafficCounterAware = 
                        InterfaceUtils.compositeIncludeType(
                            TrafficCounterAware.class, 
                            (Object[])features);
                if (null!=trafficCounterAware) {
                    try {
                        trafficCounterAware.setTrafficCounter(
                            (TrafficCounterHandler)initiator.enable(APPLY.TRAFFICCOUNTER));
                    } catch (Exception e) {
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
                    } catch (Exception e) {
                        LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                                channel, ExceptionUtils.exception2detail(e));
                    }
                }
            }};
    }

    private Observable<? extends Channel> createChannelAndConnectTo(
            final SocketAddress remoteAddress, 
            final Feature[] features) {
        return this._channelCreator.newChannel()
            .doOnNext(_SET_SNDBUF_SIZE)
            .doOnNext(_DISABLE_AUTOREAD)
            .doOnNext(applyPerChannelFeatures(features))
            .flatMap(RxNettys.asyncConnectToMaybeSSL(remoteAddress));
    }
    
    public static Action1<Channel> applyPerChannelFeatures(
            final Feature[] features) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                Nettys.applyFeaturesToChannel(null, 
                    channel, 
                    HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, 
                    features);
                Nettys.applyHandler(channel.pipeline(), APPLY.HTTPCLIENT);
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
            pooled ? new DefaultChannelPool() : Nettys.unpoolChannels(), 
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
    
    @Override
    public void close() {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }
    
    private int _lowWaterMark = -1;
    private int _highWaterMark = -1;
    private int _sendBufSize = -1;
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;
}
