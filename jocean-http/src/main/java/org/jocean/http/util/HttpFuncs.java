package org.jocean.http.util;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.FuncN;

class HttpFuncs {
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpFuncs.class);
    
    private HttpFuncs() {
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
                        Nettys.actionToRemoveHandler(ctx.channel(), this).call();
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
                    } finally {
                        Nettys.actionToRemoveHandler(ctx.channel(), this).call();
                        ctx.fireChannelRead(msg);
                    }
                }
            };
        }
    };
    
    static final Func1<Action0, ChannelHandler> ON_CHANNEL_READCOMPLETE_FUNC1 = 
            new Func1<Action0, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action0 onChannelReadComplete) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void channelReadComplete(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ON_CHANNEL_READCOMPLETE_FUNC1: channel({})/handler({}): channelReadComplete.", 
                                ctx.channel(), ctx.name());
                    }
                    try {
                        onChannelReadComplete.call();
                    } finally {
                        ctx.fireChannelReadComplete();
                    }
                }
            };
        }
    };
    
    static final Func1<Action0, ChannelHandler> ON_CHANNEL_WRITABILITYCHANGED_FUNC1 = 
            new Func1<Action0, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action0 onChannelWritabilityChanged) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void channelWritabilityChanged(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ON_CHANNEL_WRITABILITYCHANGED_FUNC1: channel({})/handler({}): channelWritabilityChanged.", 
                                ctx.channel(), ctx.name());
                    }
                    try {
                        onChannelWritabilityChanged.call();
                    } finally {
                        ctx.fireChannelWritabilityChanged();
                    }
                }
            };
        }
    };
}
