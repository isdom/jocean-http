package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCounted;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jocean.event.api.annotation.GuardPaired;
import org.jocean.http.client.impl.AbstractChannelPool;
import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.PairedVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func0;
import rx.functions.Func2;
import rx.functions.FuncN;

public class Nettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys.class);

    private Nettys() {
        throw new IllegalStateException("No instances!");
    }
    
    public interface OnHttpObject {
        @GuardPaired(paired={"org.jocean.http.util.Nettys._NETTY_REFCOUNTED_GUARD"})
        public void onHttpObject(final HttpObject httpObject);
        public void onError(Throwable e);
    }
    
    public static ChannelPool unpoolChannels(final ChannelCreator channelCreator) {
        return new AbstractChannelPool(channelCreator) {
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
                        LOG.debug("channel({}): add ({}) handler before({}).", pipeline.channel(), name, entry.getKey());
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
            LOG.debug("channel({}): add ({}) handler at last.", pipeline.channel(), name);
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
    
    public static final FuncN<ChannelHandler> HTTPSERVER_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpServerCodec();
        }};
        
    public static final FuncN<ChannelHandler> CONTENT_COMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentCompressor();
        }};
        
    public static final Func2<Channel,SslContext,ChannelHandler> SSL_FUNC2 = 
            new Func2<Channel,SslContext,ChannelHandler>() {
        @Override
        public ChannelHandler call(final Channel channel, final SslContext ctx) {
            return ctx.newHandler(channel.alloc());
        }};
            
    public static final Func2<Channel,Integer,ChannelHandler> CLOSE_ON_IDLE_FUNC2 = 
            new Func2<Channel,Integer,ChannelHandler>() {
                @Override
                public ChannelHandler call(final Channel channel, final Integer allIdleTimeout) {
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
    
    public static final Func2<Channel, OnHttpObject, ChannelHandler> HTTPSERVER_WORK_FUNC2 = 
            new Func2<Channel, OnHttpObject, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Channel channel,
                final OnHttpObject onHttpObject) {
            return new SimpleChannelInboundHandler<HttpObject>() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx,
                        Throwable cause) throws Exception {
                    LOG.warn("exceptionCaught {}, detail:{}", ctx.channel(),
                            ExceptionUtils.exception2detail(cause));
                    onHttpObject.onError(cause);
                    ctx.close();
                }

                // @Override
                // public void channelReadComplete(ChannelHandlerContext ctx) {
                // ctx.flush();
                // }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    onHttpObject.onError(new RuntimeException("channelInactive"));
                }

                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject msg) throws Exception {
                    onHttpObject.onHttpObject(msg);
                }

                // @Override
                // public void channelActive(final ChannelHandlerContext ctx)
                // throws Exception {
                // }
            };
        }
    };
}
