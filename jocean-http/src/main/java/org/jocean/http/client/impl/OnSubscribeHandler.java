package org.jocean.http.client.impl;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Subscriber;

final class OnSubscribeHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static final Logger LOG =
            LoggerFactory.getLogger(OnSubscribeHandler.class);
    
    private final Subscriber<? super HttpObject> _subscriber;

    OnSubscribeHandler(final Subscriber<? super HttpObject> subscriber) {
        this._subscriber = subscriber;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("channelInactive: ch({})", ctx.channel());
        }
        ctx.fireChannelInactive();
        this._subscriber.onError(new RuntimeException("peer has closed."));
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx,
            final Throwable cause) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("exceptionCaught: ch({}), detail:{}", ctx.channel(),
                    ExceptionUtils.exception2detail(cause));
        }
        ctx.close();
        this._subscriber.onError(cause);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,
            final HttpObject msg) throws Exception {
        this._subscriber.onNext(msg);
        if (msg instanceof LastHttpContent) {
            /*
             * netty 参考代码: https://github.com/netty/netty/blob/netty-
             * 4.0.26.Final /codec/src /main/java/io/netty/handler/codec
             * /ByteToMessageDecoder .java#L274 https://github.com/netty
             * /netty/blob/netty-4.0.26.Final /codec-http /src/main/java
             * /io/netty/handler/codec/http/HttpObjectDecoder .java#L398
             * 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件 currentState ==
             * State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() &&
             * !chunked 即没有指定Content-Length头域，也不是CHUNKED传输模式
             * ，此情况下，即会自动产生一个LastHttpContent .EMPTY_LAST_CONTENT实例
             * 因此，无需在channelInactive处，针对该情况做特殊处理
             */
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelRead0: ch({}) recv LastHttpContent:{}",
                        ctx.channel(), msg);
            }
            final ChannelPool pool = ChannelPool.Util
                    .getChannelPool(ctx.channel());
            if (null != pool) {
                pool.afterReceiveLastContent(ctx.channel());
            }
            this._subscriber.onCompleted();
        }
    }
}
