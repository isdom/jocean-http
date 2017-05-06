package io.netty.handler.traffic;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * 流量统计工具,统计收发消息数
 */
@ChannelHandler.Sharable
public class GlobalMessageTrafficMonitor extends ChannelDuplexHandler {
    private TrafficCounterExt tc;

    public GlobalMessageTrafficMonitor(TrafficCounterExt tc) {
        this.tc = tc;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        tc.messageWritten();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
        tc.messageReceived();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        tc.channelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        tc.channelUnregistered();
    }
}
