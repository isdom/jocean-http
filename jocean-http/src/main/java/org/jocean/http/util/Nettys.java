package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCounted;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jocean.http.client.impl.AbstractChannelPool;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.PairedVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;

public class Nettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys.class);

    private Nettys() {
        throw new IllegalStateException("No instances!");
    }
    
    public interface ChannelAware {
        public void setChannel(final Channel channel);
    }
    
    public interface ServerChannelAware {
        public void setServerChannel(final ServerChannel serverChannel);
    }
    
    public interface OnHttpObject {
        //@GuardPaired(paired={"org.jocean.http.util.Nettys._NETTY_REFCOUNTED_GUARD"})
        public void onHttpObject(final HttpObject httpObject);
        public void onError(Throwable e);
    }
    
    public static ChannelPool unpoolChannels() {
        return new AbstractChannelPool() {
            @Override
            protected Channel reuseChannel(final SocketAddress address) {
                return null;
            }
            
            @Override
            public boolean recycleChannel(final Channel channel) {
                channel.close();
                return false;
            }
    
            @Override
            public void beforeSendRequest(Channel channel, HttpRequest request) {
            }
    
            @Override
            public void afterReceiveLastContent(Channel channel) {
            }
        };
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
        
    public static interface ToOrdinal extends Func2<String,ChannelHandler,Integer> {}
    
    public static ChannelHandler insertHandler(
            final ChannelPipeline pipeline, 
            final String name, 
            final ChannelHandler handler,
            final ToOrdinal toOrdinal) {
        final int toInsertOrdinal = toOrdinal.call(name, handler);
        final Iterator<Entry<String,ChannelHandler>> itr = pipeline.iterator();
        while (itr.hasNext()) {
            final Entry<String,ChannelHandler> entry = itr.next();
            try {
                final int order = toOrdinal.call(entry.getKey(), entry.getValue())
                        - toInsertOrdinal;
                if (order==0) {
                    //  equals, handler already added, just ignore
                    //  NOT added
                    LOG.warn("channel({}): handler ({}) already added, ignore.", pipeline.channel(), name);
                    return null;
                }
                if (order < 0) {
                    // current handler's name less than name, continue comapre
                    continue;
                }
                if (order > 0) {
                    //  OK, add handler before current handler
                    pipeline.addBefore(entry.getKey(), name, handler);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({}): add ({}/{}) handler before({}).", pipeline.channel(), name, handler, entry.getKey());
                    }
                    return handler;
                }
            } catch (IllegalArgumentException e) {
                // throw by toOrdinal.call, so just ignore this entry and continue
                LOG.warn("insert handler named({}), meet handler entry:{}, which is !NOT! ordinal, just ignore", 
                        name, entry);
                continue;
            }
        }
        pipeline.addLast(name, handler);
        if (LOG.isDebugEnabled()) {
            LOG.debug("channel({}): add ({}/{}) handler at last.", pipeline.channel(), name, handler);
        }
        return handler;
    }
    
    public static <E extends Enum<E>> ToOrdinal ordinal(final Class<E> cls) {
        return new ToOrdinal() {
            @Override
            public Integer call(final String name, final ChannelHandler handler) {
                if (handler instanceof Ordered) {
                    return ((Ordered)handler).ordinal();
                }
                else {
                    return Enum.valueOf(cls, name).ordinal();
                }
            }};
    }
    
    public static Func0<String[]> namesDifferenceBuilder(final Channel channel) {
        final List<String> orgs = channel.pipeline().names();
        return new Func0<String[]>() {
            @Override
            public String[] call() {
                final List<String> current = channel.pipeline().names();
                current.removeAll(orgs);
                return current.toArray(new String[0]);
            }};
    }
    
    public static final Func2<Channel,SslContext,ChannelHandler> SSL_FUNC2 = 
            new Func2<Channel,SslContext,ChannelHandler>() {
        @Override
        public ChannelHandler call(final Channel channel, final SslContext ctx) {
            return ctx.newHandler(channel.alloc());
        }};
        
    public static final Func1<Integer,ChannelHandler> CLOSE_ON_IDLE_FUNC1 = 
        new Func1<Integer,ChannelHandler>() {
            @Override
            public ChannelHandler call(final Integer allIdleTimeout) {
              return new IdleStateHandler(0, 0, allIdleTimeout) {
                  @Override
                  protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
                      if (LOG.isInfoEnabled()) {
                          LOG.info("channelIdle:{} , close channel[{}]", evt.state().name(), ctx.channel());
                      }
                      ctx.channel().close();
                  }
              };
          }
    };
}
