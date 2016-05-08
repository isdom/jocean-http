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
import org.jocean.http.util.RxNettys.DefaultDoOnUnsubscribe;
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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
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
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final Feature... features) {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> responseSubscriber) {
                if (!responseSubscriber.isUnsubscribed()) {
                    try {
                        final Feature[] fullFeatures = 
                                JOArrays.addFirst(Feature[].class, 
                                        cloneFeatures(features.length > 0 ? features : _defaultFeatures), 
                                        HttpClientConstants.APPLY_HTTPCLIENT);
                        final DefaultDoOnUnsubscribe doOnUnsubscribe = new DefaultDoOnUnsubscribe();
                        
                        _channelPool.retainChannel(remoteAddress)
                            .doOnNext(ChannelPool.Util.actionEnableRecyclingReuseChannel(doOnUnsubscribe))
                            .doOnNext(RxNettys.actionUndoableApplyFeatures(
                                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, fullFeatures, doOnUnsubscribe))
                            .onErrorResumeNext(createChannel(remoteAddress, fullFeatures, doOnUnsubscribe))
                            .doOnNext(implFeatures(fullFeatures, doOnUnsubscribe))
                            .flatMap(doSendRequest(request, fullFeatures))
                            .flatMap(waitforResponse(doOnUnsubscribe))
                            .doOnUnsubscribe(new Action0() {
                                @Override
                                public void call() {
                                    doOnUnsubscribe.unsubscribe();
                                }})
                            .subscribe(responseSubscriber);
                    } catch (final Throwable e) {
                        responseSubscriber.onError(e);
                    }
                } else {
                    LOG.warn("defineInteraction: responseSubscriber {} has unsubscribed", responseSubscriber);
                }
            }});
    }

    protected Action1<? super Channel> implFeatures(
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        final ChannelAware channelAware = 
                InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);
        final TrafficCounterAware trafficCounterAware = 
                InterfaceUtils.compositeIncludeType(TrafficCounterAware.class, (Object[])features);
        
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                fillChannelAware(channel, channelAware);
                hookTrafficCounter(channel, trafficCounterAware, doOnUnsubscribe);
            }};
    }

    private Func1<Channel, Observable<HttpObject>> waitforResponse(final DoOnUnsubscribe doOnUnsubscribe) {
        return new Func1<Channel, Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call(final Channel channel) {
                return Observable.create(new OnSubscribe<HttpObject>() {
                    @Override
                    public void call(final Subscriber<? super HttpObject> subscriber) {
                        final ChannelHandler handler = 
                                APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), new Subscriber<HttpObject>(subscriber) {
                                    @Override
                                    public void onCompleted() {
                                        final ChannelPool pool = ChannelPool.Util
                                                .getChannelPool(channel);
                                        if (null != pool) {
                                            pool.postReceiveLastContent(channel);
                                        }
                                        subscriber.onCompleted();
                                    }

                                    @Override
                                    public void onError(final Throwable e) {
                                        subscriber.onError(e);
                                    }

                                    @Override
                                    public void onNext(final HttpObject httpObj) {
                                        subscriber.onNext(httpObj);
                                    }});
                        doOnUnsubscribe.call(Subscriptions.create(RxNettys.actionToRemoveHandler(channel, handler)));
                    }});
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
            final TrafficCounterAware trafficCounterAware, 
            final DoOnUnsubscribe doOnUnsubscribe) {
        if (null!=trafficCounterAware) {
            try {
                trafficCounterAware.setTrafficCounter(buildTrafficCounter(channel, doOnUnsubscribe));
            } catch (Exception e) {
                LOG.warn("exception when invoke setTrafficCounter for channel ({}), detail: {}",
                        channel, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private TrafficCounter buildTrafficCounter(
            final Channel channel, 
            final DoOnUnsubscribe doOnUnsubscribe) {
        final TrafficCounterHandler handler = 
                (TrafficCounterHandler)APPLY.TRAFFICCOUNTER.applyTo(channel.pipeline());
        
        doOnUnsubscribe.call(Subscriptions.create(RxNettys.actionToRemoveHandler(channel, handler)));
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

    private Func1<Channel, Observable<Channel>> doSendRequest(
            final Observable<? extends Object> request,
            final Feature[] features) {
        final ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeIncludeType(
                    ApplyToRequest.class,
                    InterfaceUtils.compositeBySource(
                        ApplyToRequest.class, HttpClientConstants._CLS_TO_APPLY2REQ, features),
                    InterfaceUtils.compositeIncludeType(
                        ApplyToRequest.class, (Object[])features));
        return new Func1<Channel, Observable<Channel>> () {
            @Override
            public Observable<Channel> call(final Channel channel) {
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        request.doOnNext(doOnRequest(applyToRequest, channel))
                            .doOnCompleted(new Action0() {
                                @Override
                                public void call() {
                                    channel.flush();
                                    subscriber.onNext(channel);
                                    subscriber.onCompleted();
                                }})
                            .map(DefaultHttpClient.<Object>sendMessage(channel))
                            .doOnNext(new Action1<ChannelFuture>() {
                                @Override
                                public void call(final ChannelFuture future) {
                                    RxNettys.attachFutureToSubscriber(future, subscriber);
                                }})
                            .flatMap(RxNettys.<ChannelFuture,Channel>flatMapByNever())
                            .subscribe(subscriber);
                    }});
            }
        };
    }

    private static <M> Func1<M, ChannelFuture> sendMessage(final Channel channel) {
        return new Func1<M, ChannelFuture>() {
            @Override
            public ChannelFuture call(final M msg) {
                return channel.write(ReferenceCountUtil.retain(msg));
            }
        };
    }
    
    private Action1<Object> doOnRequest(final ApplyToRequest applyToRequest, final Channel channel) {
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest) {
                    if (null!=applyToRequest) {
                        applyToRequest.call((HttpRequest)msg);
                    }
                    _channelPool.preSendRequest(channel, (HttpRequest)msg);
                }
            }
        };
    }
    
    private Observable<? extends Channel> createChannel(
            final SocketAddress remoteAddress, 
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        return Observable.create(new OnSubscribe<ChannelFuture>() {
            @Override
            public void call(final Subscriber<? super ChannelFuture> futureSubscriber) {
                if (!futureSubscriber.isUnsubscribed()) {
                    final ChannelFuture future = _channelCreator.newChannel();
                    doOnUnsubscribe.call(Subscriptions.from(future));
                    futureSubscriber.onNext(future);
                    futureSubscriber.onCompleted();
                }
            }})
            .doOnNext(ChannelPool.Util.actionEnableRecyclingForNewChannel(_channelPool, doOnUnsubscribe))
            .flatMap(RxNettys.funcFutureToChannel())
            .doOnNext(RxNettys.actionPermanentlyApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, features))
            .doOnNext(RxNettys.actionUndoableApplyFeatures(
                    HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, features, doOnUnsubscribe))
            .flatMap(RxNettys.funcAsyncConnectTo(remoteAddress, doOnUnsubscribe))
            .compose(RxNettys.markChannelWhenReady(isSSLEnabled(features)))
            ;
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
