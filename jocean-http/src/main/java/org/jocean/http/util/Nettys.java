package org.jocean.http.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCounted;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jocean.event.api.annotation.GuardPaired;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.impl.AbstractChannelPool;
import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.PairedVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func2;
import rx.functions.Func3;
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
            public void recycleChannel(final Channel channel) {
                channel.close();
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
        
    public static final FuncN<ChannelHandler> HTTPCLIENT_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpClientCodec();
        }};
            
    public static final FuncN<ChannelHandler> CONTENT_DECOMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentDecompressor();
        }};
        
    public static final FuncN<ChannelHandler> CHUNKED_WRITER_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new ChunkedWriteHandler();
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
                public ChannelHandler call(final Channel channel, Integer allIdleTimeout) {
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

    private static final class Ready4InteractionNotifier extends
            ChannelInboundHandlerAdapter {
        private final boolean _enableSSL;
        private final Subscriber<? super Channel> _subscriber;

        private Ready4InteractionNotifier(
                final boolean enableSSL,
                final Subscriber<? super Channel> subscriber) {
            this._enableSSL = enableSSL;
            this._subscriber = subscriber;
        }

        private void removeSelf(final ChannelHandlerContext ctx) {
            final ChannelPipeline pipeline = ctx.pipeline();
            if (pipeline.context(this) != null) {
                pipeline.remove(this);
            }
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx)
                throws Exception {
            if (!_enableSSL) {
                removeSelf(ctx);
                _subscriber.onNext(ctx.channel());
                _subscriber.onCompleted();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channel({}): Ready4InteractionNotifier.channelActive", ctx.channel());
                }
            }
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx,
                final Object evt) throws Exception {
            if (_enableSSL && evt instanceof SslHandshakeCompletionEvent) {
                final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                if (sslComplete.isSuccess()) {
                    removeSelf(ctx);
                    _subscriber.onNext(ctx.channel());
                    _subscriber.onCompleted();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({}): Ready4InteractionNotifier.userEventTriggered for ssl handshake success", ctx.channel());
                    }
                } else {
                    _subscriber.onError(sslComplete.cause());
                    LOG.warn("channel({}): Ready4InteractionNotifier.userEventTriggered for ssl handshake failure:{}", 
                            ctx.channel(), ExceptionUtils.exception2detail(sslComplete.cause()));
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    public static final Func3<Channel,Boolean,Subscriber<? super Channel>,ChannelHandler> 
        READY4INTERACTION_NOTIFIER_FUNC3 = 
        new Func3<Channel,Boolean,Subscriber<? super Channel>,ChannelHandler>() {

            @Override
            public ChannelHandler call(
                    final Channel channel, 
                    final Boolean isSSLEnabled,
                    final Subscriber<? super Channel> subscriber) {
                return new Ready4InteractionNotifier(isSSLEnabled, subscriber);
            }} ;

    public static final Func3<Channel,Subscriber<Object>,Long,ChannelHandler> PROGRESSIVE_FUNC3 = 
            new Func3<Channel,Subscriber<Object>,Long,ChannelHandler>() {
                @Override
                public ChannelHandler call(final Channel channel, final Subscriber<Object> subscriber, final Long minIntervalInMs) {
                    return new ChannelDuplexHandler() {
                        long _lastTimestamp = -1;
                        long _uploadProgress = 0;
                        long _downloadProgress = 0;
                        
                        private void onNext4UploadProgress(final Subscriber<Object> subscriber) {
                            final long uploadProgress = this._uploadProgress;
                            this._uploadProgress = 0;
                            subscriber.onNext(new HttpClient.UploadProgressable() {
                                @Override
                                public long progress() {
                                    return uploadProgress;
                                }
                            });
                        }
                
                        private void notifyUploadProgress(final ByteBuf byteBuf) {
                            this._uploadProgress += byteBuf.readableBytes();
                            final long now = System.currentTimeMillis();
                            if (this._lastTimestamp > 0
                                && (now - this._lastTimestamp) < minIntervalInMs) {
                                return;
                            }
                            this._lastTimestamp = now;
                            onNext4UploadProgress(subscriber);
                        }

                        private void notifyDownloadProgress(final ByteBuf byteBuf) {
                            if (this._uploadProgress > 0) {
                                onNext4UploadProgress(subscriber);
                            }
                            
                            this._downloadProgress += byteBuf.readableBytes();
                            final long now = System.currentTimeMillis();
                            if (this._lastTimestamp > 0
                                && (now - this._lastTimestamp) < minIntervalInMs) {
                                return;
                            }
                            this._lastTimestamp = now;
                            final long downloadProgress = this._downloadProgress;
                            this._downloadProgress = 0;
                            subscriber.onNext(new HttpClient.DownloadProgressable() {
                                @Override
                                public long progress() {
                                    return downloadProgress;
                                }
                            });
                        }
                        
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                            if (msg instanceof ByteBuf) {
                                notifyDownloadProgress((ByteBuf)msg);
                            } else if (msg instanceof ByteBufHolder) {
                                notifyDownloadProgress(((ByteBufHolder)msg).content());
                            }
                            ctx.fireChannelRead(msg);
                        }

                        @Override
                        public void write(final ChannelHandlerContext ctx, Object msg, final ChannelPromise promise) throws Exception {
                            if (msg instanceof ByteBuf) {
                                notifyUploadProgress((ByteBuf)msg);
                            } else if (msg instanceof ByteBufHolder) {
                                notifyUploadProgress(((ByteBufHolder)msg).content());
                            }
                            ctx.write(msg, promise);
                        }
                    };
                }
        
    };

    public static final Func3<Channel,Subscriber<? super HttpObject>,ChannelPool,ChannelHandler> HTTPCLIENT_WORK_FUNC3 = 
            new Func3<Channel,Subscriber<? super HttpObject>,ChannelPool,ChannelHandler>() {
                @Override
                public ChannelHandler call(
                        final Channel channel,
                        final Subscriber<? super HttpObject> subscriber, 
                        final ChannelPool channelPool) {
                    return new SimpleChannelInboundHandler<HttpObject>() {
                        @Override
                        public void channelInactive(final ChannelHandlerContext ctx)
                                throws Exception {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("channelInactive: ch({})", ctx.channel());
                            }
                            ctx.fireChannelInactive();
                            subscriber.onError(new RuntimeException("peer has closed."));
                        }
    
                        @Override
                        public void exceptionCaught(final ChannelHandlerContext ctx,
                                final Throwable cause) throws Exception {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("exceptionCaught: ch({}), detail:{}", ctx.channel(),
                                        ExceptionUtils.exception2detail(cause));
                            }
                            ctx.close();
                            subscriber.onError(cause);
                        }
    
                        @Override
                        protected void channelRead0(final ChannelHandlerContext ctx,
                                final HttpObject msg) throws Exception {
                            subscriber.onNext(msg);
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
                                channelPool.afterReceiveLastContent(ctx.channel());
                                subscriber.onCompleted();
                            }
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
