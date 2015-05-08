/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.util.ChannelMarker;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
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
    public Observable<? extends Object> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final OutboundFeature.Applicable... features) {
        final OutboundFeature.Applicable[] applyFeatures = 
                features.length > 0 ? features : this._defaultFeatures;
        final OutboundFeature.ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeByType(applyFeatures, OutboundFeature.ApplyToRequest.class);
        final Func1<Channel, Observable<ChannelFuture>> transferRequest = 
                new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(doWhenRequest(channel))
                        .map(RxNettys.<Object>sendMessage(channel));
            }
            private final Action1<Object> doWhenRequest(final Channel channel) {
                return new Action1<Object> () {
                    @Override
                    public void call(final Object msg) {
                        if (msg instanceof HttpRequest) {
                            _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                            if (null!=applyToRequest) {
                                applyToRequest.applyToRequest((HttpRequest) msg);
                            }
                        }
                    }
                };
            }
        };
        return Observable.create(new OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<? super Object> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        channelObservable(remoteAddress, applyFeatures, subscriber)
                            .flatMap(transferRequest)
                            .flatMap(RxNettys.<ChannelFuture, Object>emitErrorOnFailure())
                            .subscribe(subscriber);
                    } catch (final Throwable e) {
                        subscriber.onError(e);
                    }
                }
            }});
    }

    private Observable<? extends Channel> channelObservable(
            final SocketAddress remoteAddress, 
            final OutboundFeature.Applicable[] features,
            final Subscriber<? super Object> subscriber) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> channelSubscriber) {
                _channelPool.retainChannel(remoteAddress)
                .subscribe(
                    new Action1<Channel>() {
                        @Override
                        public void call(final Channel channel) {
                            final HandlersClosure handlersClosure = 
                                    Nettys.channelHandlersClosure(channel);
                            for (OutboundFeature.Applicable applicable : features) {
                                if (applicable.isRemovable()) {
                                    handlersClosure.call(applicable.call(channel));
                                }
                            }
                            
                            handlersClosure.call(
                                OutboundFeature.PROGRESSIVE.applyTo(channel, subscriber));
                            
                            handlersClosure.call(
                                OutboundFeature.WORKER.applyTo(channel, subscriber, _channelPool));
                            
                            channelSubscriber.add(channelClosure(remoteAddress, channel, handlersClosure));
                            
                            if (_channelMarker.isChannelConnected(channel)) {
                                channelSubscriber.onNext(channel);
                                channelSubscriber.onCompleted();
                            } else {
                                startToConnect(channel, 
                                        remoteAddress,
                                        features, 
                                        channelSubscriber,
                                        handlersClosure);
                            }
                        }}, 
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable e) {
                            channelSubscriber.onError(e);
                        }});
        }});
    }
        
    private void startToConnect(
            final Channel channel,
            final SocketAddress remoteAddress,
            final OutboundFeature.Applicable[] features,
            final Subscriber<? super Channel> channelSubscriber,
            final HandlersClosure handlersClosure) {
        for ( OutboundFeature.Applicable applicable : features) {
            if (!applicable.isRemovable()) {
                applicable.call(channel);
            }
        }
        
        OutboundFeature.HTTPCLIENT_CODEC.applyTo(channel);
        OutboundFeature.CHUNKED_WRITER.applyTo(channel);
        
        handlersClosure.call(
            OutboundFeature.CONNECTING_NOTIFIER.applyTo(
                channel, 
                OutboundFeature.isSSLEnabled(channel.pipeline()), 
                this._channelMarker, 
                channelSubscriber));
        
        RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
            .call(channel.connect(remoteAddress))
            .subscribe(channelSubscriber);
        if (LOG.isDebugEnabled()) {
            LOG.debug("createChannel and add codecs success for channel:{}/remoteAddress:{}",channel,remoteAddress);
        }
    }

    private Subscription channelClosure(
            final SocketAddress remoteAddress,
            final Channel channel, 
            final HandlersClosure handlersClosure) {
        final AtomicBoolean isUnsubscribed = new AtomicBoolean(false);
        return new Subscription() {
            @Override
            public void unsubscribe() {
                if (isUnsubscribed.compareAndSet(false, true)) {
                    try {
                        handlersClosure.close();
                    } catch (IOException e) {
                    }
                    if (!channel.isActive() || !_channelMarker.isChannelConnected(channel)
                        || !_channelPool.recycleChannel(remoteAddress, channel)) {
                        channel.close();
                    }
                }
            }
            @Override
            public boolean isUnsubscribed() {
                return isUnsubscribed.get();
            }};
    }

    public DefaultHttpClient(final OutboundFeature.Applicable... defaultFeatures) {
        this(1, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }}), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }}),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final OutboundFeature.Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }}),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(new DefaultChannelPool(channelCreator), new DefaultChannelMarker(), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final OutboundFeature.Applicable... defaultFeatures) {
        this(channelPool, new DefaultChannelMarker(), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final ChannelMarker channelMarker,
            final OutboundFeature.Applicable... defaultFeatures) {
        this._channelPool = channelPool;
        this._defaultFeatures = defaultFeatures;
        this._channelMarker = channelMarker;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        //  TODO
//        this._channelCreator.close();
    }

    private final ChannelPool _channelPool;
    private final ChannelMarker _channelMarker;
    private final OutboundFeature.Applicable[] _defaultFeatures;
}
