/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.event.api.EventEngine;
import org.jocean.http.server.HttpServer;
import org.jocean.http.server.HttpTrade;
import org.jocean.http.server.InboundFeature;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServer implements HttpServer {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    public interface ChannelRecycler {
        public void onResponseCompleted(final Channel channel, final boolean isKeepAlive);
    }
    
    /* (non-Javadoc)
     * @see org.jocean.http.server.HttpServer#create(java.net.SocketAddress)
     */
    @Override
    public Observable<HttpTrade> create(
            final SocketAddress localAddress,
            final InboundFeature.Applicable... features) {
        return Observable.create(new OnSubscribe<HttpTrade>() {
            @Override
            public void call(final Subscriber<? super HttpTrade> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    bootstrap.childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            final ChannelPipeline pipeline = ch.pipeline();
                            for (InboundFeature.Applicable feature : features) {
                                feature.call(ch);
                            }
                            Nettys.insertHandler(
                                    pipeline,
                                    InboundFeature.HTTPSERVER_CODEC.name(), 
                                    new HttpServerCodec(),
                                    InboundFeature.TO_ORDINAL);
                            subscriber.onNext(createHttpTrade(ch, subscriber));
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    subscriber.add(Subscriptions.from(future));
                    subscriber.add(RxNettys.subscriptionFrom(future.channel()));
                    future.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future)
                                throws Exception {
                            if (!future.isSuccess()) {
                                subscriber.onError(future.cause());
                            }
                        }});
                }
            }});
    }

    private DefaultHttpTrade createHttpTrade(
            final Channel channel, final Subscriber<? super HttpTrade> subscriber) {
        return new DefaultHttpTrade(channel, this._engine, 
                createChannelRecycler(subscriber));
    }

    private ChannelRecycler createChannelRecycler(final Subscriber<? super HttpTrade> subscriber) {
        return new ChannelRecycler() {
            @Override
            public void onResponseCompleted(
                    final Channel channel, final boolean isKeepAlive) {
                //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
                //  Detail:
                //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
                //
                //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                //
                // See https://github.com/netty/netty/issues/2983 for more information.
                if (isKeepAlive && !subscriber.isUnsubscribed()) {
                    subscriber.onNext(createHttpTrade(channel, subscriber));
                    channel.flush();
                } else {
                    channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
                }
            }};
    }

    public DefaultHttpServer(final EventEngine engine, final BootstrapCreator creator) {
        this._engine = engine;
        this._creator = creator;
    }

    @Override
    public void close() throws IOException {
        this._creator.close();
    }
    
    private final BootstrapCreator _creator;
    private final EventEngine _engine;
}
