package org.jocean.http.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jocean.http.TransportException;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;
import rx.subscriptions.Subscriptions;

class FACTORYFUNCS {
    private static final Logger LOG =
            LoggerFactory.getLogger(FACTORYFUNCS.class);
    
    private FACTORYFUNCS() {
        throw new IllegalStateException("No instances!");
    }
    
    static final FuncN<ChannelHandler> TRAFFICCOUNTER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new TrafficCounterHandler();
        }};
        
    static final FuncN<ChannelHandler> HTTPCLIENT_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpClientCodec();
        }};
            
    static final FuncN<ChannelHandler> CONTENT_DECOMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentDecompressor();
        }};
        
    static final FuncN<ChannelHandler> CHUNKED_WRITER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new ChunkedWriteHandler();
        }};

    static final FuncN<ChannelHandler> HTTPSERVER_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpServerCodec();
        }};
        
    static final FuncN<ChannelHandler> CONTENT_COMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentCompressor();
        }
    };

    static final Func2<Channel,SslContext,ChannelHandler> SSL_FUNC2 = 
            new Func2<Channel,SslContext,ChannelHandler>() {
        @Override
        public ChannelHandler call(final Channel channel, final SslContext ctx) {
            return ctx.newHandler(channel.alloc());
        }};
        
    static final Func1<Integer,ChannelHandler> CLOSE_ON_IDLE_FUNC1 = 
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
    
    static final Func2<Action1<Channel>, Action1<Throwable>, ChannelHandler> SSLNOTIFY_FUNC2 = 
            new Func2<Action1<Channel>, Action1<Throwable>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action1<Channel> onSuccess,
                final Action1<Throwable> onFailure) {
            return new  ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(final ChannelHandlerContext ctx,
                        final Object evt) throws Exception {
                    if (evt instanceof SslHandshakeCompletionEvent) {
                        RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                        final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                        if (sslComplete.isSuccess()) {
                            try {
                                onSuccess.call(ctx.channel());
                            } catch (Exception e) {
                                LOG.warn("exception when invoke onSuccess, detail:{}",
                                        ExceptionUtils.exception2detail(e));
                            }
                        } else {
                            try {
                                onFailure.call(sslComplete.cause());
                            } catch (Exception e) {
                                LOG.warn("exception when invoke onFailure, detail:{}",
                                        ExceptionUtils.exception2detail(e));
                            }
                        }
                    }
                    ctx.fireUserEventTriggered(evt);
                }
            };
        }
    };
    
    static final Func1<Action0, ChannelHandler> ON_CHANNEL_READ_FUNC1 = 
            new Func1<Action0, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action0 onChannelRead) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx,
                        final Throwable cause) throws Exception {
                    LOG.warn("ON_CHANNEL_READ_FUNC1: exceptionCaught at channel({})/handler({}), detail:{}", 
                            ctx.channel(), this,
                            ExceptionUtils.exception2detail(cause));
                    ctx.close();
                }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ON_CHANNEL_READ_FUNC1: channel({})/handler({}): channelInactive.", 
                                ctx.channel(), ctx.name());
                    }
                    ctx.close();
                }

                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ON_CHANNEL_READ_FUNC1: channel({})/handler({}): channelRead with msg({}).", 
                                ctx.channel(), ctx.name(), msg);
                    }
                    try {
                        onChannelRead.call();
                        ctx.fireChannelRead(msg);
                    } finally {
                        RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                    }
                }
            };
        }
    };
    
    static final Func1<Subscriber<? super HttpObject>, ChannelHandler> HTTPOBJ_SUBSCRIBER_FUNC1 = 
            new Func1<Subscriber<? super HttpObject>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Subscriber<? super HttpObject> subscriber) {
            return new SimpleChannelInboundHandler<HttpObject>(true) {
                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx,
                        final Throwable cause) throws Exception {
                    LOG.warn("exceptionCaught at channel({})/handler({}), detail:{}, and call ({}).onError with TransportException.", 
                            ctx.channel(), ctx.name(),
                            ExceptionUtils.exception2detail(cause), 
                            subscriber);
                    touchAllContentWith(ctx.channel(), "exceptionCaught:" + ExceptionUtils.exception2detail(cause));
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onError(new TransportException("exceptionCaught of" + ctx.channel(), cause));
                    }
                    ctx.close();
                }

                // @Override
                // public void channelReadComplete(ChannelHandlerContext ctx) {
                // ctx.flush();
                // }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelInactive and call ({}).onError with TransportException.", 
                                ctx.channel(), ctx.name(), subscriber);
                    }
                    
                    touchAllContentWith(ctx.channel(), "channelInactive");
                    if (!subscriber.isUnsubscribed()) {
                        subscriber.onError(new TransportException("channelInactive of " + ctx.channel()));
                    }
                }

                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject msg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        if (msg instanceof ByteBufHolder) {
                            LOG.debug("channelRead0: channel({})/handler({}), call ({}).onNext with ByteBufHolder's content: {}.", 
                                    ctx.channel(), ctx.name(), subscriber, Nettys.dumpByteBufHolder((ByteBufHolder)msg));
                        } else {
                            LOG.debug("channelRead0: channel({})/handler({}), call ({}).onNext with HttpObject: {}.", 
                                    ctx.channel(), ctx.name(), subscriber, msg);
                        }
                    }
                    
                    if (msg instanceof HttpRequest) {
                        initTouchableWithRequest(ctx.channel(), (HttpRequest)msg);
                    } else if (msg instanceof HttpResponse) {
                        initTouchableWithResponse(ctx.channel(), (HttpResponse)msg);
                    }
                    
                    if (msg instanceof HttpContent) {
                        touchAndHoldContent(ctx.channel(), (HttpContent)msg);
                    }
                    
                    if (!subscriber.isUnsubscribed()) {
                        try {
                            subscriber.onNext(msg);
                        } catch (Exception e) {
                            LOG.warn("exception when invoke onNext for channel({})/msg ({}), detail: {}.", 
                                    ctx.channel(), msg, ExceptionUtils.exception2detail(e));
                        }
                    }
                    
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
                        //  remove handler itself
                        RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                        try {
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.onCompleted();
                            }
                        } catch (Exception e) {
                            LOG.warn("exception when invoke onCompleted for channel({}), detail: {}.", 
                                    ctx.channel(), ExceptionUtils.exception2detail(e));
                        }
                    }
                }

                // @Override
                // public void channelActive(final ChannelHandlerContext ctx)
                // throws Exception {
                // }
            };
        }
    };
    
    private static final AttributeKey<String> ATTR_TOUCH_HINT = 
            AttributeKey.valueOf("__HTTP_TOUCH_HINT");
    
    private static final AttributeKey<Queue<HttpContent>> ATTR_HTTPCONTENTS = 
            AttributeKey.valueOf("__HTTPCONTENTS");
    
    private static void clearContentsOnUnsubscribe(final Channel channel) {
        RxNettys.doOnUnsubscribe(channel, Subscriptions.create(new Action0() {
            @Override
            public void call() {
                final Queue<HttpContent> queue = channel.attr(ATTR_HTTPCONTENTS).getAndSet(null);
                if (null != queue) {
                    queue.clear();
                }
            }}));
    }
    private static void initTouchableWithRequest(final Channel channel, final HttpRequest req) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append(channel.toString());
        sb.append('|');
        sb.append(req.headers().get("remoteip", ""));
        sb.append('|');
        sb.append(req.method().name());
        sb.append('|');
        sb.append(req.uri());
        sb.append('|');
        sb.append(req.headers().get(HttpHeaderNames.CONTENT_TYPE, ""));
        sb.append('|');
        sb.append(req.headers().get(HttpHeaderNames.CONTENT_LENGTH, ""));
        sb.append(']');
        channel.attr(ATTR_TOUCH_HINT).set(sb.toString());
        channel.attr(ATTR_HTTPCONTENTS).set(new ConcurrentLinkedQueue<HttpContent>());
        clearContentsOnUnsubscribe(channel);
    }

    private static void initTouchableWithResponse(final Channel channel, final HttpResponse resp) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append(channel.toString());
        sb.append('|');
        sb.append(resp.status().code());
        sb.append('|');
        sb.append(resp.headers().get(HttpHeaderNames.CONTENT_TYPE, ""));
        sb.append('|');
        sb.append(resp.headers().get(HttpHeaderNames.CONTENT_LENGTH, ""));
        sb.append(']');
        channel.attr(ATTR_TOUCH_HINT).set(sb.toString());
        channel.attr(ATTR_HTTPCONTENTS).set(new ConcurrentLinkedQueue<HttpContent>());
        clearContentsOnUnsubscribe(channel);
    }

    private static void touchAndHoldContent(final Channel channel, final HttpContent msg) {
        final StringBuilder sb = new StringBuilder();
        sb.append(channel.attr(ATTR_TOUCH_HINT).get());
        sb.append("refCnt(");
        sb.append(msg.refCnt());
        sb.append(")[");
        sb.append(msg.toString());
        sb.append("]");
        msg.touch(sb.toString());
        final Queue<HttpContent> queue = channel.attr(ATTR_HTTPCONTENTS).get();
        if (null != queue) {
            queue.add(msg);
        }
    }

    private static void touchAllContentWith(final Channel channel, final String hint) {
        String fullhint = channel.attr(ATTR_TOUCH_HINT).get();
        fullhint = (null != fullhint ? fullhint : "") + hint;
        final Queue<HttpContent> queue = channel.attr(ATTR_HTTPCONTENTS).get();
        if (null != queue) {
            for (HttpContent c : queue) {
                c.touch(fullhint);
            }
        }
    }
}
