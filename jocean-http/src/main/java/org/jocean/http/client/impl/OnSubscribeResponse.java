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
import org.jocean.idiom.Ordered;
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
    
    private final Func1<ChannelHandler, Observable<? extends Channel>> _channelObservable;
    private final Func1<Channel, Observable<ChannelFuture>> _transferRequest;
    private final ChannelPool _channelPool;
    
    OnSubscribeResponse(
        final Func1<ChannelHandler, Observable<? extends Channel>> channelObservable, 
        final ChannelPool channelPool,
        final OutboundFeature.ApplyToRequest applyToRequest,
        final Observable<? extends Object> request) {
        this._channelObservable = channelObservable;
        this._channelPool = channelPool;
        this._transferRequest = 
                new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(applyToRequest(channel))
                        .map(RxNettys.<Object>sendMessage(channel));
            }
            private final Action1<Object> applyToRequest(final Channel channel) {
                return new Action1<Object> () {
                    @Override
                    public void call(final Object msg) {
                        if (msg instanceof HttpRequest) {
                            _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                            if (null!=applyToRequest) {
                                applyToRequest.applyToRequest((HttpRequest) msg);
                            }
                        }
                    }
                };
            }
        };
    }
        
    @Override
    public void call(final Subscriber<? super HttpObject> responseSubscriber) {
        if (!responseSubscriber.isUnsubscribed()) {
            try {
                this._channelObservable.call(new WorkHandler(responseSubscriber))
                    .flatMap(this._transferRequest)
                    .flatMap(RxNettys.<ChannelFuture, HttpObject>emitErrorOnFailure())
                    .subscribe(responseSubscriber);
            } catch (final Throwable e) {
                responseSubscriber.onError(e);
            }
        }
    }

    private final class WorkHandler extends SimpleChannelInboundHandler<HttpObject>         
        implements Ordered {
        @Override
        public int ordinal() {
            return OutboundFeature.LAST_FEATURE.ordinal() + 1;
        }

        private final Subscriber<? super HttpObject> _responseSubscriber;

        private WorkHandler(Subscriber<? super HttpObject> responseSubscriber) {
            this._responseSubscriber = responseSubscriber;
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx)
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channelInactive: ch({})", ctx.channel());
            }
            ctx.fireChannelInactive();
            _responseSubscriber.onError(new RuntimeException("peer has closed."));
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                final Throwable cause) throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("exceptionCaught: ch({}), detail:{}", ctx.channel(),
                        ExceptionUtils.exception2detail(cause));
            }
            ctx.close();
            _responseSubscriber.onError(cause);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx,
                final HttpObject msg) throws Exception {
            _responseSubscriber.onNext(msg);
            if (msg instanceof LastHttpContent) {
                /*
                 * netty 参考代码:
                 * https://github.com/netty/netty/blob/netty-4.0.26.Final
                 * /codec/src
                 * /main/java/io/netty/handler/codec/ByteToMessageDecoder
                 * .java#L274
                 * https://github.com/netty/netty/blob/netty-4.0.26.Final
                 * /codec-http
                 * /src/main/java/io/netty/handler/codec/http/HttpObjectDecoder
                 * .java#L398 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件 currentState
                 * == State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() &&
                 * !chunked
                 * 即没有指定Content-Length头域，也不是CHUNKED传输模式，此情况下，即会自动产生一个LastHttpContent
                 * .EMPTY_LAST_CONTENT实例 因此，无需在channelInactive处，针对该情况做特殊处理
                 */
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channelRead0: ch({}) recv LastHttpContent:{}",
                            ctx.channel(), msg);
                }
                _channelPool.afterReceiveLastContent(ctx.channel());
                _responseSubscriber.onCompleted();
            }
        }
    }
}