package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCounted;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.PairedVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func2;
import rx.functions.Func4;
import rx.functions.FuncN;

public class Nettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(Nettys.class);

    private Nettys() {
        throw new IllegalStateException("No instances!");
    }
    
    public static HandlersClosure channelHandlersClosure(final Channel channel) {
        final List<ChannelHandler> handlers = new ArrayList<>();
        return new HandlersClosure() {

            @Override
            public ChannelHandler call(final ChannelHandler handler) {
                handlers.add(handler);
                return handler;
            }

            @Override
            public void close() throws IOException {
                final ChannelPipeline pipeline = channel.pipeline();
                for (ChannelHandler handler : handlers) {
                    try {
                        if (pipeline.context(handler) != null) {
                            pipeline.remove(handler);
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when invoke pipeline.remove, detail:{}", 
                                ExceptionUtils.exception2detail(e));
                    }
                    
                }
            }};
    }
    
    public static ChannelPool unpoolChannels(final ChannelCreator channelCreator) {
        return new ChannelPool() {
            @Override
            public Observable<? extends Channel> retainChannel(final SocketAddress address) {
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        channelCreator.newChannel()
                        .addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(
                                    final ChannelFuture future)
                                    throws Exception {
                                if (future.isSuccess()) {
                                    subscriber.onNext(future.channel());
                                    subscriber.onCompleted();
                                } else {
                                    subscriber.onError(future.cause());
                                }
                            }});
                    }});
            }
    
            @Override
            public boolean recycleChannel(final SocketAddress address, final Channel channel) {
                return false;
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
                    return null;
                }
                if (order < 0) {
                    // current handler's name less than name, continue comapre
                    continue;
                }
                if (order > 0) {
                    //  OK, add handler before current handler
                    pipeline.addBefore(entry.getKey(), name, handler);
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

    private static final class ConnectingNotifier extends
            ChannelInboundHandlerAdapter {
        private final boolean _enableSSL;
        private final ChannelMarker _channelMarker;
        private final Subscriber<? super Channel> _subscriber;

        private ConnectingNotifier(
                final boolean enableSSL,
                final ChannelMarker channelMarker,
                final Subscriber<? super Channel> subscriber) {
            this._enableSSL = enableSSL;
            this._channelMarker = channelMarker;
            this._subscriber = subscriber;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx)
                throws Exception {
            if (!_enableSSL) {
                _channelMarker.markChannelConnected(ctx.channel());
                _subscriber.onNext(ctx.channel());
                _subscriber.onCompleted();
            }
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx,
                final Object evt) throws Exception {
            if (_enableSSL && evt instanceof SslHandshakeCompletionEvent) {
                final SslHandshakeCompletionEvent sslComplete = ((SslHandshakeCompletionEvent) evt);
                if (sslComplete.isSuccess()) {
                    _channelMarker.markChannelConnected(ctx.channel());
                    _subscriber.onNext(ctx.channel());
                    _subscriber.onCompleted();
                } else {
                    _subscriber.onError(sslComplete.cause());
                }
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    public static final Func4<Channel,Boolean,ChannelMarker,Subscriber<? super Channel>,ChannelHandler> 
        CONNECTING_NOTIFIER_FUNC4 = 
        new Func4<Channel,Boolean,ChannelMarker,Subscriber<? super Channel>,ChannelHandler>() {

            @Override
            public ChannelHandler call(
                    final Channel channel, 
                    final Boolean isSSLEnabled,
                    final ChannelMarker channelMarker,
                    final Subscriber<? super Channel> subscriber) {
                return new ConnectingNotifier(isSSLEnabled, channelMarker, subscriber);
            }} ;
}
