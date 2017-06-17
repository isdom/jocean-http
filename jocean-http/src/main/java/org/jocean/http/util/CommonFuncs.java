package org.jocean.http.util;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

public class CommonFuncs {
    private static final Logger LOG =
            LoggerFactory.getLogger(CommonFuncs.class);
    
    private CommonFuncs() {
        throw new IllegalStateException("No instances!");
    }
    
    public static final Func1<Action0, ChannelHandler> ON_CHANNEL_INACTIVE_FUNC1 = 
            new Func1<Action0, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action0 onChannelInactive) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ON_CHANNEL_INACTIVE_FUNC1: channel({})/handler({}): channelInactive.", 
                                ctx.channel(), ctx.name());
                    }
                    ctx.close();
                    try {
                        onChannelInactive.call();
                    } finally {
                        //  TODO?
//                        ctx.fireChannelInactive();
                    }
                }
            };
        }
    };
    
    public static final Func1<Action1<Throwable>, ChannelHandler> ON_EXCEPTION_CAUGHT_FUNC1 = 
            new Func1<Action1<Throwable>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action1<Throwable> onExceptionCaught) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx,
                        final Throwable cause) throws Exception {
                    LOG.warn("ON_EXCEPTION_CAUGHT_FUNC1: channel({})/handler({}), detail:{}", 
                            ctx.channel(), 
                            ctx.name(),
                            ExceptionUtils.exception2detail(cause));
                    try {
                        onExceptionCaught.call(cause);
                    } finally {
                        ctx.fireExceptionCaught(cause);
                    }
                }
            };
        }
    };
    
    public static final Func1<SimpleChannelInboundHandler<?>, ChannelHandler> ON_MESSAGE_FUNC1 = 
            new Func1<SimpleChannelInboundHandler<?>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final SimpleChannelInboundHandler<?> handler) {
            return handler;
        }};
}
