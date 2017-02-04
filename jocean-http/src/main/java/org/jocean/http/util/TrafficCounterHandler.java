package org.jocean.http.util;

import java.util.concurrent.atomic.AtomicLong;

import org.jocean.http.TrafficCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

final public class TrafficCounterHandler extends ChannelDuplexHandler 
    implements TrafficCounter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TrafficCounterHandler.class);

    @Override
    public long outboundBytes() {
        return this._outboundBytes.get();
    }
    
    @Override
    public long inboundBytes() {
        return this._inboundBytes.get();
    }
    
    private void updateOutboundBytes(final ByteBuf byteBuf) {
        this._outboundBytes.addAndGet(byteBuf.readableBytes());
    }

    private void updateInboundBytes(final ByteBuf byteBuf) {
        this._inboundBytes.addAndGet(byteBuf.readableBytes());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TrafficCounterHandler.channelRead: channel {} recv {}", ctx.channel(), msg);
        }
        if (msg instanceof ByteBuf) {
            updateInboundBytes((ByteBuf) msg);
        } else if (msg instanceof ByteBufHolder) {
            updateInboundBytes(((ByteBufHolder) msg).content());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg,
            final ChannelPromise promise) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TrafficCounterHandler.write: channel {} send {}", ctx.channel(), msg);
        }
        if (msg instanceof ByteBuf) {
            updateOutboundBytes((ByteBuf) msg);
        } else if (msg instanceof ByteBufHolder) {
            updateOutboundBytes(((ByteBufHolder) msg).content());
        }
        ctx.write(msg, promise);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "TrafficCounterHandler";
    }

    private final AtomicLong _outboundBytes = new AtomicLong(0);
    private final AtomicLong _inboundBytes = new AtomicLong(0);
}
