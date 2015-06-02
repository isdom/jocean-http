/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
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
import org.jocean.http.client.OutboundFeature.APPLY_SSL;
import org.jocean.http.client.OutboundFeature.Applicable;
import org.jocean.http.client.OutboundFeature.FeaturesAware;
import org.jocean.http.client.OutboundFeature.OneoffApplicable;
import org.jocean.http.util.ChannelSubscriberAware;
import org.jocean.http.util.ResponseSubscriberAware;
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
            public void call(final Subscriber<? super Object> responseSubscriber) {
                if (!responseSubscriber.isUnsubscribed()) {
                    try {
                        _channelPool.retainChannel(remoteAddress, 
                                buildFeatures(applyFeatures, responseSubscriber))
                            .flatMap(transferRequest)
                            .flatMap(RxNettys.<ChannelFuture, Object>emitErrorOnFailure())
                            .subscribe(responseSubscriber);
                    } catch (final Throwable e) {
                        responseSubscriber.onError(e);
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

    private final static Applicable HTTPCLIENT_APPLY = new Applicable() {
        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return  OutboundFeature.HTTPCLIENT_CODEC.applyTo(pipeline);
        }
    };
    
    private static final class APPLY_READY4INTERACTION_NOTIFIER implements
            Applicable, ChannelSubscriberAware, FeaturesAware {
        @Override
        public void setChannelSubscriber(
                final Subscriber<? super Channel> subscriber) {
            this._channelSubscriber = subscriber;
        }
        @Override
        public void setApplyFeatures(final Applicable[] features) {
            for (Applicable feature : features) {
                if (feature instanceof APPLY_SSL) {
                    this._isSSLEnabled = true;
                }
            }
        }

        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return OutboundFeature.READY4INTERACTION_NOTIFIER.applyTo(pipeline,
                    this._isSSLEnabled, this._channelSubscriber);
        }

        private boolean _isSSLEnabled = false;
        private Subscriber<? super Channel> _channelSubscriber;
    }

    private static final class APPLY_WORKER implements OneoffApplicable,
            ResponseSubscriberAware {

        @Override
        public void setResponseSubscriber(final Subscriber<Object> subscriber) {
            this._responseSubscriber = subscriber;
        }

        @Override
        public ChannelHandler call(final ChannelPipeline pipeline) {
            return OutboundFeature.WORKER.applyTo(pipeline,
                    this._responseSubscriber);
        }

        private Subscriber<Object> _responseSubscriber;
    }
    
    private Applicable[] buildFeatures(
            Applicable[] features,
            final Subscriber<? super Object> responseSubscriber) {
        features = JOArrays.addFirst(Applicable[].class, features, 
                HTTPCLIENT_APPLY, new APPLY_READY4INTERACTION_NOTIFIER(), new APPLY_WORKER());
        final ResponseSubscriberAware responseSubscriberAware = 
                InterfaceUtils.compositeIncludeType(features, ResponseSubscriberAware.class);
        if (null!=responseSubscriberAware) {
            responseSubscriberAware.setResponseSubscriber(responseSubscriber);
        }
        return features;
    }

    private final ChannelPool _channelPool;
    private final Applicable[] _defaultFeatures;
}
