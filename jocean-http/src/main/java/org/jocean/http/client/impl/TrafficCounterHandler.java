package org.jocean.http.client.impl;

import java.util.concurrent.atomic.AtomicLong;

import org.jocean.http.TrafficCounter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

final class TrafficCounterHandler extends ChannelDuplexHandler 
    implements TrafficCounter {

    @Override
    public long uploadBytes() {
        return this._uploadBytes.get();
    }
    
    @Override
    public long downloadBytes() {
        return this._downloadBytes.get();
    }
    
    private void updateUploadBytes(final ByteBuf byteBuf) {
        this._uploadBytes.addAndGet(byteBuf.readableBytes());
    }

    private void updateDownloadBytes(final ByteBuf byteBuf) {
        this._downloadBytes.addAndGet(byteBuf.readableBytes());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg)
            throws Exception {
        if (msg instanceof ByteBuf) {
            updateDownloadBytes((ByteBuf) msg);
        } else if (msg instanceof ByteBufHolder) {
            updateDownloadBytes(((ByteBufHolder) msg).content());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg,
            final ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf) {
            updateUploadBytes((ByteBuf) msg);
        } else if (msg instanceof ByteBufHolder) {
            updateUploadBytes(((ByteBufHolder) msg).content());
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

    private final AtomicLong _uploadBytes = new AtomicLong(0);
    private final AtomicLong _downloadBytes = new AtomicLong(0);
}
