package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import org.jocean.http.client.OutboundFeature;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
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
    
    private final Func1<ChannelHandler, Observable<Channel>> _channelObservable;
    private final Func1<Channel, Observable<ChannelFuture>> _transferRequest;
    private final ChannelPool _channelPool;
    
    OnSubscribeResponse(
        final Func1<ChannelHandler, Observable<Channel>> channelObservable, 
        final ChannelPool channelPool,
        final OutboundFeature.ApplyToRequest applyToRequest,
        final Observable<? extends HttpObject> request) {
        this._channelObservable = channelObservable;
        this._channelPool = channelPool;
        this._transferRequest = 
                new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(addAcceptEncodingHead(channel))
                        .map(RxNettys.<HttpObject>sendMessage(channel));
            }
            private final Action1<HttpObject> addAcceptEncodingHead(final Channel channel) {
                return new Action1<HttpObject> () {
                    @Override
                    public void call(final HttpObject msg) {
                        if (msg instanceof HttpRequest) {
                            _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                            if (null!=applyToRequest) {
                                applyToRequest.applyToRequest((HttpRequest) msg);
//                                HttpHeaders.addHeader((HttpRequest) msg,
//                                    HttpHeaders.Names.ACCEPT_ENCODING, 
//                                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
                            }
                        }
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
                this._channelObservable.call(createResponseHandler(wrapper))
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
            public void channelInactive(final ChannelHandlerContext ctx)
                    throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channelInactive: ch({})", ctx.channel());
                }
                ctx.fireChannelInactive();
                response.onError(new RuntimeException("peer has closed."));
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx,
                    final Throwable cause) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("exceptionCaught: ch({}), detail:{}", 
                            ctx.channel(), ExceptionUtils.exception2detail(cause));
                }
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
                    _channelPool.afterReceiveLastContent(ctx.channel());
                    response.onCompleted();
                }
            }
        };
    }
}