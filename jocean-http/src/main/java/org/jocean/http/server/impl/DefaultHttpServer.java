/**
 * 
 */
package org.jocean.http.server.impl;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.server.HttpServer;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.PipelineApply;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.RxFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.FuncN;
import rx.functions.Functions;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServer implements HttpServer {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpServer.class);
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder) {
        return defineServer(localAddress, featuresBuilder, (Feature[])null);
    }
    
    @Override
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Feature... features) {
        return defineServer(localAddress, null, features);
    }
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder,
            final Feature... features) {
        return Observable.create(new OnSubscribe<HttpTrade>() {
            @Override
            public void call(final Subscriber<? super HttpTrade> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    abstract class Initializer extends ChannelInitializer<Channel> implements Ordered {
                        @Override
                        public String toString() {
                            return "[DefaultHttpServer' ChannelInitializer]";
                        }
                        @Override
                        public int ordinal() {
                            return -1000;
                        }
                    }
                    bootstrap.childHandler(new Initializer() {
                        @Override
                        protected void initChannel(final Channel channel) throws Exception {
                            final Feature[] actualFeatures = JOArrays.addFirst(Feature[].class, 
                                    featuresOf(featuresBuilder), features);
                            final Feature[] applyFeatures = 
                                    (null != actualFeatures && actualFeatures.length > 0 ) ? actualFeatures : _defaultFeatures;
                            for (Feature feature : applyFeatures) {
                                feature.call(_APPLY_BUILDER, channel.pipeline());
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("initChannel with feature:{}", feature);
                                }
                            }
                            APPLY.HTTPSERVER.applyTo(channel.pipeline());
                            awaitInboundRequest(channel, subscriber);
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    subscriber.add(RxNettys.subscriptionFrom(future.channel()));
                    subscriber.add(Subscriptions.from(future));
                    future.addListener(RxNettys.makeFailure2ErrorListener(subscriber));
                    future.addListener(channelFutureListenerOf(features));
                }
            }});
    }

    private void awaitInboundRequest(
            final Channel channel,
            final Subscriber<? super HttpTrade> tradeSubscriber) {
        APPLY.GUIDE.applyTo(channel.pipeline(), 
            new Action0() {
                @Override
                public void call() {
                    if (!tradeSubscriber.isUnsubscribed()) {
                        tradeSubscriber.onNext(
                            httpTradeOf(channel)
                            .addOnTradeClosed(recycleChannelAction(channel, tradeSubscriber)));
                    }
                }});
    }

    private HttpTrade httpTradeOf(final Channel channel) {
        return new DefaultHttpTrade(
                channel,
                requestObservable(channel));
    }

    private Action1<HttpTrade> recycleChannelAction(
            final Channel channel,
            final Subscriber<? super HttpTrade> tradeSubscriber) {
        return new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                if (channel.isActive()
                    && trade.isEndedWithKeepAlive()
                    && !tradeSubscriber.isUnsubscribed()) {
                    channel.flush();
                    awaitInboundRequest(channel, tradeSubscriber);
                } else {
                    //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
                    //  Detail:
                    //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
                    //
                    //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    //
                    // See https://github.com/netty/netty/issues/2983 for more information.
                    channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
                }
            }};
    }
    
    private Observable<HttpObject> requestObservable(final Channel channel) {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    APPLY.WORKER.applyTo(channel.pipeline(), subscriber);
                }
            }} );
    }
    
    public DefaultHttpServer() {
        this(1, 0, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpServer(
            final int processThreadNumberForAccept, 
            final int processThreadNumberForWork
            ) {
        this(processThreadNumberForAccept, processThreadNumberForWork, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpServer(
            final int processThreadNumberForAccept, 
            final int processThreadNumberForWork,
            final Feature... defaultFeatures) {
        this(new AbstractBootstrapCreator(
                new NioEventLoopGroup(processThreadNumberForAccept), 
                new NioEventLoopGroup(processThreadNumberForWork)) {
            @Override
            protected void initializeBootstrap(ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(NioServerSocketChannel.class);
            }}, defaultFeatures);
    }
    
    public DefaultHttpServer(
            final BootstrapCreator creator,
            final Feature... defaultFeatures) {
        this._creator = creator;
        this._defaultFeatures = null!=defaultFeatures ? defaultFeatures : Feature.EMPTY_FEATURES;
    }

    @Override
    public void close() throws IOException {
        this._creator.close();
    }
    
    private static Feature[] featuresOf(final Func0<Feature[]> featuresBuilder) {
        return null != featuresBuilder ? featuresBuilder.call() : null;
    }
    
    private static ChannelFutureListener channelFutureListenerOf(
            final Feature[] applyFeatures) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    final ServerChannelAware serverChannelAware = 
                            InterfaceUtils.compositeIncludeType(ServerChannelAware.class, 
                                (Object[])applyFeatures);
                    if (null!=serverChannelAware) {
                        try {
                            serverChannelAware.setServerChannel((ServerChannel)future.channel());
                        } catch (Exception e) {
                            LOG.warn("exception when invoke setServerChannel for channel ({}), detail: {}",
                                    future.channel(), ExceptionUtils.exception2detail(e));
                        }
                    }
                }
            }};
    }

    private final BootstrapCreator _creator;
    private final Feature[] _defaultFeatures;
    
    private static final Class2ApplyBuilder _APPLY_BUILDER;
        
    private static final FuncN<ChannelHandler> HTTPSERVER_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpServerCodec();
        }};
        
    private static final FuncN<ChannelHandler> CONTENT_COMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentCompressor();
        }
    };

    private static final Func1<Action0, ChannelHandler> HTTPSERVER_GUIDE_FUNC1 = 
            new Func1<Action0, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Action0 doWork) {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx,
                        Throwable cause) throws Exception {
                    LOG.warn("HTTPSERVER_GUIDE_FUNC1: exceptionCaught at channel({})/handler({}), detail:{}", 
                            ctx.channel(), this,
                            ExceptionUtils.exception2detail(cause));
                    ctx.close();
                }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("HTTPSERVER_GUIDE_FUNC1: channel({})/handler({}): channelInactive.", 
                                ctx.channel(), ctx.name());
                    }
                    ctx.close();
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, final Object msg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("HTTPSERVER_GUIDE_FUNC1: channel({})/handler({}): channelRead with msg({}).", 
                                ctx.channel(), ctx.name(), msg);
                    }
                    try {
                        doWork.call();
                        ctx.fireChannelRead(msg);
                    } finally {
                        RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                    }
                }
            };
        }
    };
    
    private static final Func1<Observer<HttpObject>, ChannelHandler> HTTPSERVER_WORK_FUNC1 = 
            new Func1<Observer<HttpObject>, ChannelHandler>() {
        @Override
        public ChannelHandler call(final Observer<HttpObject> httpObjectObserver) {
            return new SimpleChannelInboundHandler<HttpObject>() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx,
                        Throwable cause) throws Exception {
                    LOG.warn("exceptionCaught at channel({})/handler({}), detail:{}, and call onHttpObject({}).onError with TransportException.", 
                            ctx.channel(), ctx.name(),
                            ExceptionUtils.exception2detail(cause), 
                            httpObjectObserver);
                    httpObjectObserver.onError(new TransportException("exceptionCaught", cause));
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
                        LOG.debug("channel({})/handler({}): channelInactive and call onHttpObject({}).onError with TransportException.", 
                                ctx.channel(), ctx.name(), httpObjectObserver);
                    }
                    httpObjectObserver.onError(new TransportException("channelInactive"));
                }

                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject msg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call onHttpObject({}).onHttpObject with msg({}).", 
                                ctx.channel(), ctx.name(), httpObjectObserver, msg);
                    }
                    try {
                        httpObjectObserver.onNext(msg);
                    } catch (Exception e) {
                        LOG.warn("exception when invoke onNext for channel({})/msg ({}), detail: {}.", 
                                ctx.channel(), msg, ExceptionUtils.exception2detail(e));
                    }
                    
                    if (msg instanceof LastHttpContent) {
                        //  remove handler itself
                        RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                        try {
                            httpObjectObserver.onCompleted();
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
    
    private static enum APPLY implements PipelineApply {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC1)),
        SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
        HTTPSERVER(HTTPSERVER_CODEC_FUNCN),
        CONTENT_COMPRESSOR(CONTENT_COMPRESSOR_FUNCN),
        GUIDE(Functions.fromFunc(HTTPSERVER_GUIDE_FUNC1)),
        WORKER(Functions.fromFunc(HTTPSERVER_WORK_FUNC1)),
        ;
        
        @Override
        public ChannelHandler applyTo(final ChannelPipeline pipeline, final Object ... args) {
            if (null==this._factory) {
                throw new UnsupportedOperationException("ChannelHandler's factory is null");
            }
            return Nettys.insertHandler(
                    pipeline,
                this.name(), 
                this._factory.call(args), 
                TO_ORDINAL);
        }
    
        public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(APPLY.class);
        
        private APPLY(final FuncN<ChannelHandler> factory) {
            this._factory = factory;
        }
    
        private final FuncN<ChannelHandler> _factory;
    }
    
    static {
        _APPLY_BUILDER = new Class2ApplyBuilder();
        _APPLY_BUILDER.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _APPLY_BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_COMPRESSOR);
        _APPLY_BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _APPLY_BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
        
    }
}
