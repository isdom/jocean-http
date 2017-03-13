/**
 * 
 */
package org.jocean.http.client.impl;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.TrafficCounterAware;
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
    
    public int getOutboundLowWaterMark() {
        return this._outboundLowWaterMark;
    }

    public void setOutboundLowWaterMark(final int low) {
        this._outboundLowWaterMark = low;
    }
    
    public int getOutboundHighWaterMark() {
        return this._outboundHighWaterMark;
    }

    public void setOutboundHighWaterMark(final int high) {
        this._outboundHighWaterMark = high;
    }
    
    public int getOutboundSendBufSize() {
        return this._outboundSendBufSize;
    }

    public void setOutboundSendBufSize(final int outboundSendBufSize) {
        this._outboundSendBufSize = outboundSendBufSize;
    }
    
    public int getInboundBlockSize() {
        return this._inboundBlockSize;
    }

    public void setInboundBlockSize(final int inboundBlockSize) {
        this._inboundBlockSize = inboundBlockSize;
    }
    
    private final Action1<HttpInitiator> _doRecycleChannel = new Action1<HttpInitiator>() {
        @Override
        public void call(final HttpInitiator initiator) {
            final Channel channel = (Channel)initiator.transport();
            if (((DefaultHttpInitiator)initiator).isEndedWithKeepAlive()) {
                if (_channelPool.recycleChannel(channel)) {
                    // recycle success
                    // perform read for recv FIN SIG and to change state to close
                    channel.read();
                }
            } else {
                channel.close();
            }
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
    
    public Observable<? extends HttpInitiator> initiator0(
            final SocketAddress remoteAddress,
            final Feature... features) {
        final Feature[] fullFeatures = 
                Feature.Util.union(cloneFeatures(Feature.Util.union(this._defaultFeatures, features)),
                    HttpClientConstants.APPLY_HTTPCLIENT);
        return this._channelPool.retainChannel(remoteAddress)
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    // disable autoRead
                    channel.config().setAutoRead(false);
                }})
            .onErrorResumeNext(createChannelAndConnectTo(remoteAddress, fullFeatures))
            .doOnNext(processFeatures(fullFeatures))
            .map(new Func1<Channel, HttpInitiator>() {
                @Override
                public HttpInitiator call(final Channel channel) {
                    final DefaultHttpInitiator initiator = new DefaultHttpInitiator(channel, _doRecycleChannel);
                    initiator.inbound().messageHolder().setMaxBlockSize(_inboundBlockSize);
                    
                    if (_outboundLowWaterMark >= 0 
                        && _outboundHighWaterMark >= 0
                        && _outboundHighWaterMark >= _outboundLowWaterMark) {
                        initiator.outbound().setWriteBufferWaterMark(_outboundLowWaterMark, _outboundHighWaterMark);
                    }
                    
                    initiator.setApplyToRequest(buildApplyToRequest(fullFeatures));
                    RxNettys.applyFeaturesToChannel(
                            channel, 
                            HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, 
                            fullFeatures, 
                            initiator.onTerminate());
                    
                    final TrafficCounterAware trafficCounterAware = 
                            InterfaceUtils.compositeIncludeType(TrafficCounterAware.class, (Object[])fullFeatures);
                    if (null!=trafficCounterAware) {
                        try {
                            trafficCounterAware.setTrafficCounter(initiator.trafficCounter());
                        } catch (Exception e) {
                            LOG.warn("exception when invoke setTrafficCounter for channel ({}), detail: {}",
                                    channel, ExceptionUtils.exception2detail(e));
                        }
                    }
                    
                    return initiator;
                }});
    }
    
    private static ApplyToRequest buildApplyToRequest(final Feature[] features) {
        return InterfaceUtils.compositeIncludeType(
            ApplyToRequest.class,
            InterfaceUtils.compositeBySource(
                ApplyToRequest.class, HttpClientConstants._CLS_TO_APPLY2REQ, features),
            InterfaceUtils.compositeIncludeType(
                ApplyToRequest.class, (Object[])features));
    }
    
    private static Action1<? super Channel> processFeatures(final Feature[] features) {
        final ChannelAware channelAware = 
                InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);
        
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("dump outbound channel({})'s config: \n{}", channel, Nettys.dumpChannelConfig(channel.config()));
                }
                fillChannelAware(channel, channelAware);
            }};
    }

    private static void fillChannelAware(final Channel channel, ChannelAware channelAware) {
        if (null!=channelAware) {
            try {
                channelAware.setChannel(channel);
            } catch (Exception e) {
                LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                        channel, ExceptionUtils.exception2detail(e));
            }
        }
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
    
    private Observable<? extends Channel> createChannelAndConnectTo(
            final SocketAddress remoteAddress, 
            final Feature[] features) {
        return this._channelCreator.newChannel()
            .doOnNext(_setSendBufSize)
            .doOnNext(RxNettys.actionPermanentlyApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, features))
            .flatMap(RxNettys.asyncConnectTo(remoteAddress))
            .compose(RxNettys.markAndPushChannelWhenReady(isSSLEnabled(features)));
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
    
    private int _outboundLowWaterMark = -1;
    private int _outboundHighWaterMark = -1;
    private int _outboundSendBufSize = -1;
    private int _inboundBlockSize = 0;
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;

    private final Action1<Channel> _setSendBufSize = new Action1<Channel>() {
        @Override
        public void call(final Channel channel) {
            if ( _outboundSendBufSize > 0) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channel({})'s default SO_SNDBUF is {} bytes, and will be reset to {} bytes",
                            channel, 
                            channel.config().getOption(ChannelOption.SO_SNDBUF), 
                            _outboundSendBufSize);
                }
                channel.config().setOption(ChannelOption.SO_SNDBUF, _outboundSendBufSize);
            }
        }};
}
