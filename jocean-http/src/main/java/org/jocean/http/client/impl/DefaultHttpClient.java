/**
 * 
 */
package org.jocean.http.client.impl;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TrafficCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.RxNettys.DoOnUnsubscribe;
import org.jocean.http.util.TrafficCounterAware;
import org.jocean.http.util.TrafficCounterHandler;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Single;
import rx.functions.Action1;
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
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClient.class);
    
    @Override
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final Feature... features) {
        final Feature[] fullFeatures = 
                JOArrays.addFirst(Feature[].class, 
                        cloneFeatures(features.length > 0 ? features : _defaultFeatures), 
                        HttpClientConstants.APPLY_HTTPCLIENT);
                        
//                        _channelPool.retainChannelAsSingle(remoteAddress)
//                            .doOnSuccess(RxNettys.<Channel>enableReleaseChannelWhenUnsubscribe(doOnUnsubscribe))
//                            .doOnSuccess(RxNettys.actionUndoableApplyFeatures(
//                                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, fullFeatures, doOnUnsubscribe))
//                            .onErrorResumeNext(createChannelAndConnectToAsSingle(remoteAddress, fullFeatures, doOnUnsubscribe))
//                            .doOnSuccess(implFeatures(fullFeatures, doOnUnsubscribe))
//                            .flatMap(sendRequestThenPushChannelAsSingle(request, fullFeatures, doOnUnsubscribe))
//                            .flatMapObservable(waitforResponse(doOnUnsubscribe))
            return _channelPool.retainChannel(remoteAddress)
                .doOnNext(RxNettys.actionUndoableApplyFeatures(
                        HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, fullFeatures))
                .onErrorResumeNext(createChannelAndConnectTo(remoteAddress, fullFeatures))
                .doOnNext(hookFeatures(fullFeatures))
                .flatMap(sendRequestThenPushChannel(request, fullFeatures))
                .flatMap(waitforResponse());
    }

    protected Action1<? super Channel> hookFeatures(final Feature[] features) {
        final ChannelAware channelAware = 
                InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);
        final TrafficCounterAware trafficCounterAware = 
                InterfaceUtils.compositeIncludeType(TrafficCounterAware.class, (Object[])features);
        
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                fillChannelAware(channel, channelAware);
                hookTrafficCounter(channel, trafficCounterAware);
            }};
    }

    private void fillChannelAware(final Channel channel, ChannelAware channelAware) {
        if (null!=channelAware) {
            try {
                channelAware.setChannel(channel);
            } catch (Exception e) {
                LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                        channel, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private void hookTrafficCounter(
            final Channel channel,
            final TrafficCounterAware trafficCounterAware) {
        if (null!=trafficCounterAware) {
            try {
                trafficCounterAware.setTrafficCounter(buildTrafficCounter(channel));
            } catch (Exception e) {
                LOG.warn("exception when invoke setTrafficCounter for channel ({}), detail: {}",
                        channel, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private TrafficCounter buildTrafficCounter(final Channel channel) {
        final TrafficCounterHandler handler = 
                (TrafficCounterHandler)APPLY.TRAFFICCOUNTER.applyTo(channel.pipeline());
        
        RxNettys.doOnUnsubscribe(channel, 
                Subscriptions.create(RxNettys.actionToRemoveHandler(channel, handler)));
        return handler;
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

    @SuppressWarnings("unused")
    private Func1<Channel, Single<? extends Channel>> sendRequestThenPushChannelAsSingle(
            final Observable<? extends Object> request,
            final Feature[] features, 
            final DoOnUnsubscribe doOnUnsubscribe) {
        return new Func1<Channel, Single<? extends Channel>> () {
            @Override
            public Single<? extends Channel> call(final Channel channel) {
                return sendRequestThenPushChannel(request, features)
                        .call(channel)
                        .toSingle();
            }
        };
    }

    private Func1<Channel, Observable<? extends Channel>> sendRequestThenPushChannel(
            final Observable<? extends Object> request,
            final Feature[] features) {
        return new Func1<Channel, Observable<? extends Channel>> () {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return request.doOnNext(doOnRequest(features, channel))
                    .compose(ChannelPool.Util.hookPreSendHttpRequest(channel))
                    .compose(RxNettys.<Object>sendRequestThenPushChannel(channel));
            }
        };
    }
    
    private Action1<Object> doOnRequest(final Feature[] features, final Channel channel) {
        final ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeIncludeType(
                    ApplyToRequest.class,
                    InterfaceUtils.compositeBySource(
                        ApplyToRequest.class, HttpClientConstants._CLS_TO_APPLY2REQ, features),
                    InterfaceUtils.compositeIncludeType(
                        ApplyToRequest.class, (Object[])features));
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest) {
                    if (null!=applyToRequest) {
                        try {
                            applyToRequest.call((HttpRequest)msg);
                        } catch (Exception e) {
                            LOG.warn("exception when invoke applyToRequest.call, detail: {}",
                                    ExceptionUtils.exception2detail(e));
                        }
                    }
                }
            }
        };
    }
    
    private Func1<Channel, Observable<? extends HttpObject>> waitforResponse() {
        return new Func1<Channel, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final Channel channel) {
                return RxNettys.waitforHttpResponse().call(channel)
                    .compose(ChannelPool.Util.hookPostReceiveLastContent(channel));
            }};
    }

    @SuppressWarnings("unused")
    private Single<? extends Channel> createChannelAndConnectToAsSingle(
            final SocketAddress remoteAddress, 
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        return this._channelCreator.newChannelAsSingle(doOnUnsubscribe)
            .doOnSuccess(ChannelPool.Util.attachToChannelPoolAndEnableRecycle(_channelPool))
            .doOnSuccess(RxNettys.actionPermanentlyApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, features))
            .doOnSuccess(RxNettys.actionUndoableApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, features))
            .flatMap(RxNettys.asyncConnectToAsSingle(remoteAddress, doOnUnsubscribe))
            .compose(RxNettys.markAndPushChannelWhenReadyAsSingle(isSSLEnabled(features)));
    }

    private Observable<? extends Channel> createChannelAndConnectTo(
            final SocketAddress remoteAddress, 
            final Feature[] features) {
        return this._channelCreator.newChannel()
            .doOnNext(ChannelPool.Util.attachToChannelPoolAndEnableRecycle(_channelPool))
            .doOnNext(RxNettys.actionPermanentlyApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, features))
            .doOnNext(RxNettys.actionUndoableApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, features))
            .flatMap(RxNettys.asyncConnectTo(remoteAddress))
            .compose(RxNettys.markAndPushChannelWhenReady(isSSLEnabled(features)));
    }
    
    private Feature[] cloneFeatures(final Feature[] features) {
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

    public DefaultHttpClient(final int processThreadNumber) {
        this(processThreadNumber, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient() {
        this(0, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient(final Feature... defaultFeatures) {
        this(0, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final Feature... defaultFeatures) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            new DefaultChannelPool(), 
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
    private final Feature[] _defaultFeatures;
}
