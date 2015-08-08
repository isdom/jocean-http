/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.idiom.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public abstract class AbstractChannelCreator implements ChannelCreator {
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelCreator.class);

    protected AbstractChannelCreator() {
        class Initializer extends ChannelInitializer<Channel> implements Ordered {
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
        this._bootstrap = new Bootstrap()
            .handler(new Initializer());
        initializeBootstrap(this._bootstrap);
    }
    
    protected abstract void initializeBootstrap(final Bootstrap bootstrap);
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._bootstrap.group()
            .shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS)
            .syncUninterruptibly();
    }

    /* (non-Javadoc)
     * @see org.jocean.http.client.impl.ChannelCreator#newChannel()
     */
    @Override
    public ChannelFuture newChannel() {
        final ChannelFuture future = this._bootstrap.register();
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("create new channel: {}", future.channel());
        }
        return future;
    }
    
    public int getActiveChannelCount() {
        return this._activeChannelCount.get();
    }

    private final Bootstrap _bootstrap;
    
    private final AtomicInteger _activeChannelCount = new AtomicInteger(0);
}
