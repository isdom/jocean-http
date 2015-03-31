package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCounted;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

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
        
    public static ChannelHandler insertHandler(
            final ChannelPipeline pipeline, final ChannelHandler handler,
            final String name, final Comparator<String> comparator) {
        final Iterator<Entry<String,ChannelHandler>> itr = pipeline.iterator();
        while (itr.hasNext()) {
            final Entry<String,ChannelHandler> entry = itr.next();
            final int order = comparator.compare(entry.getKey(), name);
            if (order==0) {
                //  equals, handler already added, just ignore
                //  NOT added
                return null;
            }
            if (order < 0) {
                // current handler's name less than name, continue comapre
                continue;
            }
            if (order > 0) {
                //  OK, add handler before current handler
                pipeline.addBefore(entry.getKey(), name, handler);
                return handler;
            }
        }
        pipeline.addLast(name, handler);
        return handler;
    }
    
    public static <E extends Enum<E>> Comparator<String> comparator(final Class<E> cls) {
        return new Comparator<String>() {
            @Override
            public int compare(final String o1, final String o2) {
                return Enum.valueOf(cls, o1).ordinal() - Enum.valueOf(cls, o2).ordinal();
            }};
    }
    
}
