/**
 * 
 */
package org.jocean.http.client.impl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.Subscriber;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public abstract class AbstractChannelCreator implements ChannelCreator {
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelCreator.class);

    private static class Initializer extends ChannelInitializer<Channel> implements Ordered {
        @Override
        public int ordinal() {
            return -1000;
        }
        @Override
        protected void initChannel(Channel ch) throws Exception {
            /*
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    _activeChannelCount.incrementAndGet();
                    ctx.fireChannelActive();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    _activeChannelCount.decrementAndGet();
                    ctx.fireChannelInactive();
                }
            }); */
        }
        @Override
        public String toString() {
            return "[AbstractChannelCreator' ChannelInitializer]";
        }
    }
    
    public void reset() {
        close();
        this._bootstrap = new Bootstrap()
                .handler(new Initializer());
            initializeBootstrap(this._bootstrap);
    }
    
    protected AbstractChannelCreator() {
        reset();
    }
    
    protected abstract void initializeBootstrap(final Bootstrap bootstrap);
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() {
        if (null!=this._bootstrap) {
            // Shut down executor threads to exit.
            this._bootstrap.group()
                .shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS)
                .syncUninterruptibly();
            this._bootstrap = null;
        }
    }

    @Override
    public Single<? extends Channel> newChannelAsSingle(final DoOnUnsubscribe doOnUnsubscribe) {
        return Single.create(new Single.OnSubscribe<ChannelFuture>() {
            @Override
            public void call(final SingleSubscriber<? super ChannelFuture> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ChannelFuture future = _bootstrap.register();
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("create new channel: {}", future.channel());
                    }
                    doOnUnsubscribe.call(Subscriptions.from(future));
                    subscriber.onSuccess(future);
                }
            }})
            .doOnSuccess(RxNettys.<ChannelFuture>enableReleaseChannelWhenUnsubscribe())
            .flatMap(RxNettys.singleFutureToChannel());
    }
    
    @Override
    public Observable<? extends Channel> newChannel() {
        return Observable.create(new Observable.OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ChannelFuture future = _bootstrap.register();
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("create new channel: {}", future.channel());
                    }
                    RxNettys.installDoOnUnsubscribe(future.channel(), DoOnUnsubscribe.Util.from(subscriber));
                    subscriber.add(Subscriptions.from(future));
                    subscriber.add(RxNettys.subscriptionForReleaseChannel(future.channel()));
                    future.addListener(RxNettys.listenerOfOnError(subscriber))
                        .addListener(RxNettys.listenerOfOnNextAndCompleted(subscriber));
                }
            }});
    }

    public int getActiveChannelCount() {
        return this._activeChannelCount.get();
    }

    private Bootstrap _bootstrap = null;
    
    private final AtomicInteger _activeChannelCount = new AtomicInteger(0);
}
