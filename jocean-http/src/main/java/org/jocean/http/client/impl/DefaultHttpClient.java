/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.OutboundFeature;
import org.jocean.http.client.OutboundFeature.Applicable;
import org.jocean.http.client.OutboundFeature.OneoffApplicable;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
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
    
    @SuppressWarnings("unused")
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
            final Applicable... features) {
        final Applicable[] applyFeatures = 
                features.length > 0 ? features : this._defaultFeatures;
        final OutboundFeature.ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeIncludeType(applyFeatures, OutboundFeature.ApplyToRequest.class);
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
                        _channelPool.retainChannel(remoteAddress, 
                                buildFeatures(applyFeatures, subscriber))
                            .flatMap(transferRequest)
                            .flatMap(RxNettys.<ChannelFuture, Object>emitErrorOnFailure())
                            .subscribe(subscriber);
                    } catch (final Throwable e) {
                        subscriber.onError(e);
                    }
                }
            }});
    }

    public DefaultHttpClient(final Applicable... defaultFeatures) {
        this(1, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final Applicable... defaultFeatures) {
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
            final Applicable... defaultFeatures) { 
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
            final Applicable... defaultFeatures) { 
        this(new DefaultChannelPool(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }}),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final Applicable... defaultFeatures) {
        this(new DefaultChannelPool(channelCreator), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelPool channelPool,
            final Applicable... defaultFeatures) {
        this._channelPool = channelPool;
        this._defaultFeatures = defaultFeatures;
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

    final static Applicable HTTPCLIENT_APPLY = new Applicable() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return  OutboundFeature.HTTPCLIENT_CODEC.applyTo(channel);
        }
    };
    
    final static Applicable CHUNKED_WRITER_APPLY = new Applicable() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return  OutboundFeature.CHUNKED_WRITER.applyTo(channel);
        }
    };
    
    private Applicable[] buildFeatures(
            Applicable[] features,
            final Subscriber<? super Object> subscriber) {
        features = JOArrays.addFirst(features, 
                HTTPCLIENT_APPLY, Applicable[].class);
        features = JOArrays.addFirst(features, 
                CHUNKED_WRITER_APPLY, Applicable[].class);
        features = JOArrays.addFirst(features, 
            new OneoffApplicable() {
                @Override
                public ChannelHandler call(final Channel channel) {
                    return  OutboundFeature.PROGRESSIVE.applyTo(channel, subscriber, 100L);
                }
            }, Applicable[].class);
        features = JOArrays.addFirst(features, 
            new OneoffApplicable() {
                @Override
                public ChannelHandler call(final Channel channel) {
                    return  OutboundFeature.WORKER.applyTo(channel, subscriber, _channelPool);
                }
            }, Applicable[].class);
        return features;
    }

    private final ChannelPool _channelPool;
    private final Applicable[] _defaultFeatures;
}
