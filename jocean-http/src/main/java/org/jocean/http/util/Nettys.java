package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCounted;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.PairedVisitor;
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
                final ChannelPipeline pipeline = channel.pipeline();
                for (ChannelHandler handler : handlers) {
                    try {
                        if (pipeline.context(handler) != null) {
                            pipeline.remove(handler);
                        }
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
    
    public static PairedVisitor<Object> _NETTY_REFCOUNTED_GUARD = new PairedVisitor<Object>() {
        @Override
        public void visitBegin(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).retain();
            }
        }
        @Override
        public void visitEnd(final Object obj) {
            if ( obj instanceof ReferenceCounted ) {
                ((ReferenceCounted)obj).release();
            }
        }
        @Override
        public String toString() {
            return "NettyUtils._NETTY_REFCOUNTED_GUARD";
        }};
}
