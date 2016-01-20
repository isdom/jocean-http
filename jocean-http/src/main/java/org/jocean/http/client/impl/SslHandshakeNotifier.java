package org.jocean.http.client.impl;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import rx.Subscriber;

final class SslHandshakeNotifier extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = LoggerFactory
            .getLogger(OnSubscribeHandler.class);
    private final Subscriber<? super Channel> _subscriber;

    SslHandshakeNotifier(final Subscriber<? super Channel> subscriber) {
        this._subscriber = subscriber;
    }

    private void removeSelf(final ChannelHandlerContext ctx) {
        final ChannelPipeline pipeline = ctx.pipeline();
        if (pipeline.context(this) != null) {
            pipeline.remove(this);
        }
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx,
            final Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent) {
            removeSelf(ctx);
            final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
            if (sslComplete.isSuccess()) {
                ChannelPool.Util.setChannelReady(ctx.channel());
                this._subscriber.onNext(ctx.channel());
                this._subscriber.onCompleted();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channel({}): userEventTriggered for ssl handshake success",
                            ctx.channel());
                }
            } else {
                this._subscriber.onError(sslComplete.cause());
                LOG.warn("channel({}): userEventTriggered for ssl handshake failure:{}",
                        ctx.channel(),
                        ExceptionUtils.exception2detail(sslComplete.cause()));
            }
        }
        ctx.fireUserEventTriggered(evt);
    }
}
