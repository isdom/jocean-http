/**
 * 
 */
package org.jocean.redis.impl;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.TransportException;
import org.jocean.http.client.impl.AbstractChannelCreator;
import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.http.client.impl.DefaultChannelPool;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultRedisClient implements RedisClient {
    
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultRedisClient.class);
    
    @Override
    public Observable<? extends RedisConnection> getConnection() {
        return null == this._defaultRemoteAddress 
                ? Observable.<RedisConnection>error(new RuntimeException("No Default Redis Server"))
                : this._channelPool.retainChannel(this._defaultRemoteAddress)
                    .map(channel2RedisConnection())
                    .onErrorResumeNext(createChannelAndConnectTo(this._defaultRemoteAddress))
                    ;
    }
    
    @Override
    public Observable<? extends RedisConnection> getConnection(final SocketAddress remoteAddress) {
        return this._channelPool.retainChannel(remoteAddress)
                .map(channel2RedisConnection())
                .onErrorResumeNext(createChannelAndConnectTo(remoteAddress))
                ;
    }

    private Func1<Channel, RedisConnection> channel2RedisConnection() {
        return new Func1<Channel, RedisConnection>() {
            @Override
            public RedisConnection call(final Channel channel) {
                return new RedisConnection() {
                    @Override
                    public Observable<? extends RedisMessage> defineInteraction(
                            final Func1<DoOnUnsubscribe, Observable<? extends RedisMessage>> requestProvider) {
                        return prepareRecvResponse(channel)
                            .flatMap(buildAndSendRequest(requestProvider))
                            .compose(RxObservables.<RedisMessage>ensureSubscribeAtmostOnce())
                            ;
                    }

                    @Override
                    public Observable<? extends RedisMessage> defineInteraction(
                            final Observable<? extends RedisMessage> request) {
                        return defineInteraction(new Func1<DoOnUnsubscribe, Observable<? extends RedisMessage>>() {
                            @Override
                            public Observable<? extends RedisMessage> call(final DoOnUnsubscribe doOnUnsubscribe) {
                                return request;
                            }});
                    }};
            }};
    }

    private Observable<? extends Object> prepareRecvResponse(final Channel channel) {
        return Observable.create(new Observable.OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<? super Object> subscriber) {
                //  TODO: now just add business handler at last
                final ChannelHandler handler = buildSubscribHandler(subscriber);
                channel.pipeline().addLast(handler);
                RxNettys.doOnUnsubscribe(channel, 
                    Subscriptions.create(RxNettys.actionToRemoveHandler(channel, handler)));
                if ( !subscriber.isUnsubscribed()) {
                    subscriber.onNext(channel);
                }
            }});
    }
    
    private Func1<Object, Observable<? extends RedisMessage>> buildAndSendRequest(
            final Func1<DoOnUnsubscribe, Observable<? extends RedisMessage>> requestProvider) {
        return new Func1<Object, Observable<? extends RedisMessage>>() {
            @Override
            public Observable<? extends RedisMessage> call(final Object obj) {
                if (obj instanceof Channel) {
                    final Channel channel = (Channel)obj;
                    return safeBuildRequestByProvider(requestProvider, channel)
                        .doOnCompleted(flushWhenCompleted(channel))
                        .flatMap(sendRequest(channel));
                } else if (obj instanceof RedisMessage) {
                    return Observable.<RedisMessage>just((RedisMessage)obj);
                } else {
                    return Observable.<RedisMessage>error(new RuntimeException("unknowm obj:" + obj));
                }
            }
        };
    }
    
    private Action0 flushWhenCompleted(final Channel channel) {
        return new Action0() {
            @Override
            public void call() {
                channel.flush();
            }};
    }
    
    private Func1<RedisMessage, Observable<? extends RedisMessage>> sendRequest(
            final Channel channel) {
        return new Func1<RedisMessage, Observable<? extends RedisMessage>>() {
            @Override
            public Observable<? extends RedisMessage> call(final RedisMessage reqmsg) {
                final ChannelFuture future = channel.write(ReferenceCountUtil.retain(reqmsg));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("send redis request msg :{}", reqmsg);
                }
                RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
                return RxNettys.observableFromFuture(future);
            }};
    }
    
    private static ChannelHandler buildSubscribHandler(final Subscriber<? super RedisMessage> subscriber) {
        return new SimpleChannelInboundHandler<RedisMessage>(true) {
            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx,
                    final Throwable cause) throws Exception {
                LOG.warn("exceptionCaught at channel({})/handler({}), detail:{}, and call ({}).onError with TransportException.", 
                        ctx.channel(), ctx.name(),
                        ExceptionUtils.exception2detail(cause), 
                        subscriber);
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onError(new TransportException("exceptionCaught", cause));
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
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onError(new TransportException("channelInactive"));
                }
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext ctx,
                    final RedisMessage msg) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channel({})/handler({}): channelRead0 and call ({}).onNext with msg({}).", 
                            ctx.channel(), ctx.name(), subscriber, msg);
                }
                
                if (!subscriber.isUnsubscribed()) {
                    try {
                        subscriber.onNext(ReferenceCountUtil.retain(msg));
                    } catch (Exception e) {
                        LOG.warn("exception when invoke onNext for channel({})/msg ({}), detail: {}.", 
                                ctx.channel(), msg, ExceptionUtils.exception2detail(e));
                    } finally {
                        RxNettys.doOnUnsubscribe(ctx.channel(), Subscriptions.create(new Action0() {
                            @Override
                            public void call() {
                                final String msgstr = msg.toString();
                                final boolean released = ReferenceCountUtil.release(msg);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("RedisMessage({}) released({}) from {}'s unsubscribe", 
                                            msgstr, released, ctx.channel());
                                }
                            }}));
                    }
                }
                
                // one req/one resp
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

            // @Override
            // public void channelActive(final ChannelHandlerContext ctx)
            // throws Exception {
            // }
        };
    }

    private Observable<? extends RedisMessage> safeBuildRequestByProvider(
            final Func1<DoOnUnsubscribe, Observable<? extends RedisMessage>> requestProvider,
            final Channel channel) {
        final Observable<? extends RedisMessage> requestObservable = 
                requestProvider.call(RxNettys.queryDoOnUnsubscribe(channel));
        return null != requestObservable 
                ? requestObservable 
                : Observable.<RedisMessage>error(new RuntimeException("Can't build request observable"));
    }

    private Observable<? extends RedisConnection> createChannelAndConnectTo(
            final SocketAddress remoteAddress) {
        final Observable<? extends RedisConnection> ret = this._channelCreator.newChannel()
            .doOnNext(ChannelPool.Util.attachToChannelPoolAndEnableRecycle(_channelPool))
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    final ChannelPipeline p = channel.pipeline();
                    p.addLast(new RedisDecoder());
                    p.addLast(new RedisBulkStringAggregator());
                    p.addLast(new RedisArrayAggregator());
                    p.addLast(new RedisEncoder());
                }})
            .flatMap(RxNettys.asyncConnectTo(remoteAddress))
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    Nettys.setChannelReady(channel);
                }})
            .map(channel2RedisConnection());
        if (null != this._fornew) {
            return ret.compose(this._fornew);
        } else {
            return ret;
        }
    }
    
    public DefaultRedisClient() {
        this(0);
    }
    
    public DefaultRedisClient(final int processThreadNumber) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final ChannelCreator channelCreator) {
        this(channelCreator, new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final ChannelCreator channelCreator,
            final ChannelPool channelPool) {
        this._channelCreator = channelCreator;
        this._channelPool = channelPool;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }
    
    public void setFornew(final Transformer<? super RedisConnection, ? extends RedisConnection> fornew) {
        this._fornew = fornew;
    }
    
    public void setDefaultRedisServer(final SocketAddress _defaultRedisServerAddr) {
        this._defaultRemoteAddress = _defaultRedisServerAddr;
    }
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private SocketAddress _defaultRemoteAddress;
    private Transformer<? super RedisConnection, ? extends RedisConnection> _fornew = null;
}