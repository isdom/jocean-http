package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpRequest;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys.class);

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
                    try {
                        channel.pipeline().remove(handler);
                    } catch (Exception e) {
                        LOG.warn("exception when invoke pipeline.remove, detail:{}", 
                                ExceptionUtils.exception2detail(e));
                    }
                    
                }
            }};
    }
    
    private static final ChannelPool UNPOOL = new ChannelPool() {
        @Override
        public Channel retainChannel(final SocketAddress address) {
            return null;
        }

        @Override
        public boolean recycleChannel(final SocketAddress address, final Channel channel) {
            return false;
        }

        @Override
        public void beforeSendRequest(Channel channel, HttpRequest request) {
        }

        @Override
        public void afterReceiveLastContent(Channel channel) {
        }
    };

    public static ChannelPool unpoolChannels() {
        return UNPOOL;
    }
}
