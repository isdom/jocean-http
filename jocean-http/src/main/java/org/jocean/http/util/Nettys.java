package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Nettys {
    private Nettys() {
        throw new IllegalStateException("No instances!");
    }
    
    public static HandlersClosure channelHandlersClosure(final Channel channel) {
        final List<ChannelHandler> handlers = new ArrayList<>();
        return new HandlersClosure() {

            @Override
            public ChannelHandler call(final ChannelHandler handler) {
                handlers.add(handler);
                return handler;
            }

            @Override
            public void close() throws IOException {
                for (ChannelHandler handler : handlers) {
                    channel.pipeline().remove(handler);
                }
            }};
    }
}
