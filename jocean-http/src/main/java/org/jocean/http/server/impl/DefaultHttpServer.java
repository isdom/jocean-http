/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.server.HttpServer;
import org.jocean.http.util.RxNettys;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

/**
 * @author isdom
 *
 */
public class DefaultHttpServer implements HttpServer {

    /* (non-Javadoc)
     * @see org.jocean.http.server.HttpServer#create(java.net.SocketAddress)
     */
    @Override
    public Observable<Channel> create(final SocketAddress localAddress) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    bootstrap.childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            subscriber.onNext(ch);
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    subscriber.add(RxNettys.channelSubscription(future.channel()));
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

    public DefaultHttpServer(final BootstrapCreator creator) {
        this._creator = creator;
    }

    @Override
    public void close() throws IOException {
        this._creator.close();
    }
    
    private final BootstrapCreator _creator;
}
