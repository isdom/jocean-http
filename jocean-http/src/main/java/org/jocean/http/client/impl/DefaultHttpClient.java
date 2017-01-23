/**
 * 
 */
package org.jocean.http.client.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.TrafficCounter;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.ComposeSource;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.SendedMessageAware;
import org.jocean.http.util.TrafficCounterAware;
import org.jocean.http.util.TrafficCounterHandler;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
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
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClient.class);
    
    @Override
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress,
            final Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProvider,
            final Feature... features) {
        final Feature[] fullFeatures = 
            Feature.Util.union(cloneFeatures(Feature.Util.union(this._defaultFeatures, features)),
                HttpClientConstants.APPLY_HTTPCLIENT);
        return this._channelPool.retainChannel(remoteAddress)
            .doOnNext(RxNettys.actionUndoableApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, fullFeatures))
            .onErrorResumeNext(createChannelAndConnectTo(remoteAddress, fullFeatures))
            .doOnNext(hookFeatures(fullFeatures))
            .flatMap(prepareRecvResponse(fullFeatures))
            .flatMap(buildAndSendRequest(requestProvider, fullFeatures))
            ;
    }
    
    @Override
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final Feature... features) {
        return defineInteraction(remoteAddress, new Func1<DoOnUnsubscribe, Observable<? extends Object>>() {
            @Override
            public Observable<? extends Object> call(final DoOnUnsubscribe doOnUnsubscribe) {
                return request;
            }}, features);
    }
    
    @Override
    public InteractionBuilder interaction() {
        final AtomicReference<SocketAddress> _remoteAddress = new AtomicReference<>();
        final AtomicReference<Observable<? extends Object>> _request = new AtomicReference<>();
        final AtomicReference<Func1<DoOnUnsubscribe, Observable<? extends Object>>> _requestProvider 
            = new AtomicReference<>();
        final List<Feature> _features = new ArrayList<>();
        
        return new InteractionBuilder() {
            @Override
            public InteractionBuilder remoteAddress(
                    final SocketAddress remoteAddress) {
                _remoteAddress.set(remoteAddress);
                return this;
            }

            @Override
            public InteractionBuilder request(final Observable<? extends Object> request) {
                _request.set(request);
                return this;
            }
            
            @Override
            public InteractionBuilder requestProvider(
                    Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProvider) {
                _requestProvider.set(requestProvider);
                return this;
            }

            @Override
            public InteractionBuilder feature(final Feature... features) {
                for (Feature f : features) {
                    if (null != f) {
                        _features.add(f);
                    }
                }
                return this;
            }

            @Override
            public Observable<? extends HttpObject> build() {
                if (null == _remoteAddress.get()) {
                    throw new RuntimeException("remoteAddress not set");
                }
                if (null == _requestProvider.get() && null == _request.get()) {
                    throw new RuntimeException("request and requestProvider not set");
                }
                if (null != _requestProvider.get()) {
                    return defineInteraction(_remoteAddress.get(), 
                            _requestProvider.get(), 
                            _features.toArray(Feature.EMPTY_FEATURES));
                } else {
                    return defineInteraction(_remoteAddress.get(), 
                            _request.get(), 
                            _features.toArray(Feature.EMPTY_FEATURES));
                }
            }};
    }

    protected Action1<? super Channel> hookFeatures(final Feature[] features) {
        final ChannelAware channelAware = 
                InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);
        final TrafficCounterAware trafficCounterAware = 
                InterfaceUtils.compositeIncludeType(TrafficCounterAware.class, (Object[])features);
        
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("dump outbound channel({})'s config: \n{}", channel, Nettys.dumpChannelConfig(channel.config()));
                }
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

    private Func1<Channel, Observable<? extends Object>> prepareRecvResponse(final Feature[] features) {
        final ComposeSource[] composeSources = 
                InterfaceUtils.selectIncludeType(
                        ComposeSource.class, (Object[])features);;
        
        return new Func1<Channel, Observable<? extends Object>>() {
            @Override
            public Observable<? extends Object> call(final Channel channel) {
                Observable<? extends Object> channelAndRespStream = 
                        Observable.create(new Observable.OnSubscribe<Object>() {
                    @Override
                    public void call(final Subscriber<? super Object> subscriber) {
                        RxNettys.doOnUnsubscribe(channel, 
                            Subscriptions.create(RxNettys.actionToRemoveHandler(channel, 
                                APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber))));
                        if ( !subscriber.isUnsubscribed()) {
                            subscriber.onNext(channel);
                        }
                    }});
                
                if (null != composeSources) {
                    channelAndRespStream = channelAndRespStream.compose(composeSources[0].transformer(channel));
                }
                
                return channelAndRespStream
                    .compose(ChannelPool.Util.hookPostReceiveLastContent(channel))
                    ;
            }};
    }
    
    private Func1<Object, Observable<? extends HttpObject>> buildAndSendRequest(
            final Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProvider,
            final Feature[] features) {
        return new Func1<Object, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final Object obj) {
                if (obj instanceof Channel) {
                    final Channel channel = (Channel)obj;
                    return safeBuildRequestByProvider(requestProvider, channel)
                        .doOnNext(doOnRequest(features))
                        .compose(ChannelPool.Util.hookPreSendHttpRequest(channel))
                        .doOnCompleted(flushWhenCompleted(channel))
                        .flatMap(sendRequest(channel, features));
                } else if (obj instanceof HttpObject) {
                    return Observable.<HttpObject>just((HttpObject)obj);
                } else {
                    return Observable.<HttpObject>error(new RuntimeException("unknowm obj:" + obj));
                }
            }
        };
    }
    
    private Observable<? extends Object> safeBuildRequestByProvider(
            final Func1<DoOnUnsubscribe, Observable<? extends Object>> requestProvider,
            final Channel channel) {
        final Observable<? extends Object> requestObservable = 
                requestProvider.call(RxNettys.queryDoOnUnsubscribe(channel));
        return null != requestObservable 
                ? requestObservable 
                : Observable.error(new RuntimeException("Can't build request observable"));
    }

    private Action1<Object> doOnRequest(final Feature[] features) {
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
    
    private Action0 flushWhenCompleted(final Channel channel) {
        return new Action0() {
            @Override
            public void call() {
                channel.flush();
            }};
    }

    private Func1<Object, Observable<? extends HttpObject>> sendRequest(
            final Channel channel, final Feature[] features) {
        
        final boolean flushAfterWrite = 
                InterfaceUtils.selectIncludeType(
                        Feature.FLUSH_PER_WRITE.getClass(), (Object[])features) != null;
        
        final Subscriber<Object> subscriber = subscriberOfSendedMessage(features);
        
        return new Func1<Object, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final Object reqmsg) {
                final ChannelFuture future = flushAfterWrite 
                        ? channel.writeAndFlush(ReferenceCountUtil.retain(reqmsg))
                        : channel.write(ReferenceCountUtil.retain(reqmsg))
                        ;
                
                if (null != subscriber) {
                    RxNettys.channelObservableFromFuture(future).map(new Func1<Channel, Object>() {
                        @Override
                        public Object call(final Channel c) {
                            return reqmsg;
                        }})
                    .concatWith(Observable.never()) // ensure NOT push onCompleted
                    .subscribe(subscriber);
                }
                
                if (LOG.isDebugEnabled()) {
                    LOG.debug("send http request msg :{}", reqmsg);
                }
                RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
                return RxNettys.observableFromFuture(future);
            }};
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
    
    private Subscriber<Object> subscriberOfSendedMessage(final Feature[] features) {
        final SendedMessageAware sendedMessageAware = 
                InterfaceUtils.compositeIncludeType(SendedMessageAware.class, (Object[])features);
        if (null != sendedMessageAware) {
            final COWCompositeSupport<Subscriber<Object>> composite = 
                    new COWCompositeSupport<>();
            sendedMessageAware.setSendedMessage(RxObservables.asObservable(composite));
            return RxSubscribers.asSubscriber(composite);
        } else {
            return null;
        }
    }

    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;
}
