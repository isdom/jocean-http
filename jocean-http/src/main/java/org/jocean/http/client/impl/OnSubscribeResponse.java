package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

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
    
    OnSubscribeResponse(
        final int featuresAsInt,
        final Func1<ChannelHandler, Observable<Channel>> getObservable, 
        final Observable<HttpObject> request) {
        this._featuresAsInt = featuresAsInt;
        this._getObservable = getObservable;
        this._request = request;
    }
    
    @Override
    public void call(final Subscriber<? super HttpObject> responseSubscriber) {
        final Subscriber<? super HttpObject> wrapper = 
                RxSubscribers.guardUnsubscribed(responseSubscriber);
        try {
            if (!wrapper.isUnsubscribed()) {
                this._getObservable.call(createResponseHandler(wrapper))
                .flatMap(new Func1<Channel, Observable<ChannelFuture>> () {
                    @Override
                    public Observable<ChannelFuture> call(final Channel channel) {
                        return _request.doOnNext(ADD_ACCEPTENCODING_HEAD)
                                .map(RxNettys.<HttpObject>sendMessage(channel));
                    }})
                .flatMap(RxNettys.<ChannelFuture, HttpObject>checkFuture())
                .subscribe(wrapper);
            }
        } catch (final Throwable e) {
            wrapper.onError(e);
        }
    }

    private SimpleChannelInboundHandler<HttpObject> createResponseHandler(
            final Subscriber<? super HttpObject> responseSubscriber) {
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
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channelInactive: ch({})", ctx.channel());
                }
                ctx.fireChannelInactive();
                responseSubscriber.onError(new RuntimeException("peer has closed."));
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
                responseSubscriber.onError(cause);
            }

            @Override
            protected void channelRead0(
                    final ChannelHandlerContext ctx,
                    final HttpObject msg) throws Exception {
                responseSubscriber.onNext(msg);
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
                    responseSubscriber.onCompleted();
                }
            }
        };
    }
    
    final Action1<HttpObject> ADD_ACCEPTENCODING_HEAD = new Action1<HttpObject> () {
        @Override
        public void call(final HttpObject msg) {
            if (isCompressEnabled() && msg instanceof HttpRequest) {
                HttpHeaders.addHeader((HttpRequest) msg,
                    HttpHeaders.Names.ACCEPT_ENCODING, 
                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
            }
        }
        
        private boolean isCompressEnabled() {
            return !Features.isEnabled(_featuresAsInt, Feature.DisableCompress);
        }
    };
        
    /*
    private GenericFutureListener<ChannelFuture> createConnectListener(
            final Subscriber<? super HttpObject> responseSubscriber) {
        return new GenericFutureListener<ChannelFuture>() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                final Channel channel = future.channel();
                if (future.isSuccess()) {
//                    responseSubscriber.add(
                    // TODO add sending canceled testcase
                    _request.doOnNext(ADD_ACCEPTENCODING_HEAD)
                        .map(RxNettys.<HttpObject>sendMessage(channel))
                        .flatMap(RxNettys.<ChannelFuture,HttpObject>checkFuture())
                        .subscribe(responseSubscriber);
//                    );
                    responseSubscriber.add(RxNettys.channelSubscription(channel));
                } else {
                    try {
                        responseSubscriber.onError(future.cause());
                    } finally {
                        channel.close();
                    }
                }
            }
        };
    }
    */

    private final int    _featuresAsInt;
    private final Observable<HttpObject> _request;
    private final Func1<ChannelHandler, Observable<Channel>> _getObservable;
}