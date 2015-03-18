package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;

import org.jocean.http.client.HttpClient.Feature;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.Features;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;

final class OnSubscribeResponse implements
    OnSubscribe<HttpObject> {

    private static final Logger LOG =
            LoggerFactory.getLogger(OnSubscribeResponse.class);
    
    private static final AttributeKey<Boolean> KEEPALIVE = AttributeKey.valueOf("KEEPALIVE");
    
    private final Func1<ChannelHandler, Observable<Channel>> _getObservable;
    private final Func1<Channel, Observable<ChannelFuture>> _transferRequest;
    private final Action1<Channel> _markReuse;
    
    OnSubscribeResponse(
        final Func1<ChannelHandler, Observable<Channel>> getObservable, 
        final Action1<Channel> markReuse,
        final int featuresAsInt,
        final Observable<? extends HttpObject> request) {
        this._getObservable = getObservable;
        this._markReuse = markReuse;
        this._transferRequest = 
                new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(createAddAcceptEncodingHead(channel))
                        .map(RxNettys.<HttpObject>sendMessage(channel));
            }
            private final Action1<HttpObject> createAddAcceptEncodingHead(final Channel channel) {
                return new Action1<HttpObject> () {
                    @Override
                    public void call(final HttpObject msg) {
                        if (msg instanceof HttpRequest) {
                            setKeepAlive(channel, HttpHeaders.isKeepAlive((HttpMessage)msg));
                            if (isCompressEnabled()) {
                                HttpHeaders.addHeader((HttpRequest) msg,
                                    HttpHeaders.Names.ACCEPT_ENCODING, 
                                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
                            }
                        }
                    }
                    private boolean isCompressEnabled() {
                        return !Features.isEnabled(featuresAsInt, Feature.DisableCompress);
                    }
                };
            }
        };
    }
        
    @Override
    public void call(final Subscriber<? super HttpObject> response) {
        final Subscriber<? super HttpObject> wrapper = 
                RxSubscribers.guardUnsubscribed(response);
        try {
            if (!wrapper.isUnsubscribed()) {
                this._getObservable.call(createResponseHandler(wrapper))
                    .flatMap(this._transferRequest)
                    .flatMap(RxNettys.<ChannelFuture, HttpObject>checkFuture())
                    .subscribe(wrapper);
            }
        } catch (final Throwable e) {
            wrapper.onError(e);
        }
    }

    private SimpleChannelInboundHandler<HttpObject> createResponseHandler(
            final Subscriber<? super HttpObject> response) {
        return new SimpleChannelInboundHandler<HttpObject>() {

            @Override
            public void channelActive(final ChannelHandlerContext ctx)
                    throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channelActive: ch({})", ctx.channel());
                }
                ctx.fireChannelActive();
            }

            @Override
            public void channelInactive(final ChannelHandlerContext ctx)
                    throws Exception {
                ctx.fireChannelInactive();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channelInactive: ch({})", ctx.channel());
                }
                response.onError(new RuntimeException("peer has closed."));
            }

            @Override
            public void userEventTriggered(final ChannelHandlerContext ctx,
                    final Object evt) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("userEventTriggered: ch({}) with event:{}",
                            ctx.channel(), evt);
                }
                ctx.fireUserEventTriggered(evt);
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx,
                    final Throwable cause) throws Exception {
                ctx.close();
                response.onError(cause);
            }

            @Override
            protected void channelRead0(
                    final ChannelHandlerContext ctx,
                    final HttpObject msg) throws Exception {
                response.onNext(msg);
                if (msg instanceof LastHttpContent) {
                    /* netty 参考代码:
                     * https://github.com/netty/netty/blob/netty-4.0.26.Final/codec/src/main/java/io/netty/handler/codec/ByteToMessageDecoder.java#L274
                     * https://github.com/netty/netty/blob/netty-4.0.26.Final/codec-http/src/main/java/io/netty/handler/codec/http/HttpObjectDecoder.java#L398
                     * 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件
                     * currentState == State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() && !chunked
                     * 即没有指定Content-Length头域，也不是CHUNKED传输模式，此情况下，即会自动产生一个LastHttpContent.EMPTY_LAST_CONTENT实例
                     * 因此，无需在channelInactive处，针对该情况做特殊处理
                     */
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channelRead0: ch({}) recv LastHttpContent:{}",
                                ctx.channel(), msg);
                    }
                    if (isKeepAlive(ctx.channel())) {
                        _markReuse.call(ctx.channel());
                    }
                    response.onCompleted();
                }
            }
        };
    }

    private static void setKeepAlive(final Channel channel, final boolean isKeepAlive) {
        channel.attr(KEEPALIVE).set(isKeepAlive);
    }
    
    private static boolean isKeepAlive(final Channel channel) {
        return channel.attr(KEEPALIVE).get();
    }
}