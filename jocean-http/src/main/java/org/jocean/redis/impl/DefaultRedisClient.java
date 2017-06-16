/**
 * 
 */
package org.jocean.redis.impl;

import java.net.SocketAddress;

import org.jocean.http.client.impl.AbstractChannelCreator;
import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.http.client.impl.DefaultChannelPool;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

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
    
    private final static Action1<Channel> ADD_CODEC_AND_SET_READY = new Action1<Channel>() {
        @Override
        public void call(final Channel channel) {
            final ChannelPipeline p = channel.pipeline();
            Nettys.applyHandler(p, APPLY.REDIS_DECODER);
            Nettys.applyHandler(p, APPLY.REDIS_BULKSTRING_AGGREGATOR);
            Nettys.applyHandler(p, APPLY.REDIS_ARRAY_AGGREGATOR);
            Nettys.applyHandler(p, APPLY.REDIS_ENCODER);
            Nettys.setChannelReady(channel);
        }};
        
    private final Action1<RedisConnection> _doRecycleChannel = new Action1<RedisConnection>() {
        @Override
        public void call(final RedisConnection c) {
            final DefaultRedisConnection connection = (DefaultRedisConnection)c;
            final Channel channel = connection.channel();
            if (!connection.isTransacting()) {
                if (_channelPool.recycleChannel(channel)) {
                    // recycle success
                    // perform read for recv FIN SIG and to change state to close
                    channel.read();
                }
            } else {
                channel.close();
                LOG.info("close transactioning redis channel: {}", channel);
            }
        }};
        
    final Func1<Channel, RedisConnection> _channel2Connection = new Func1<Channel, RedisConnection>() {
        @Override
        public RedisConnection call(final Channel channel) {
            return new DefaultRedisConnection(channel, _doRecycleChannel);
        }};
            
    @Override
    public Observable<? extends RedisConnection> getConnection() {
        return null == this._defaultRemoteAddress 
                ? Observable.<RedisConnection>error(new RuntimeException("No Default Redis Server"))
                : getConnection(this._defaultRemoteAddress);
    }
    
    @Override
    public Observable<? extends RedisConnection> getConnection(final SocketAddress remoteAddress) {
        return this._channelPool.retainChannel(remoteAddress)
                .map(this._channel2Connection)
                .onErrorResumeNext(createChannelAndConnectTo(remoteAddress))
                ;
    }

    private Observable<? extends RedisConnection> createChannelAndConnectTo(
            final SocketAddress remoteAddress) {
        final Observable<? extends RedisConnection> ret = this._channelCreator.newChannel()
            .flatMap(RxNettys.asyncConnectTo(remoteAddress))
            .doOnNext(ADD_CODEC_AND_SET_READY)
            .map(this._channel2Connection);
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
    
    @Override
    public void close() {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }
    
    public void setFornew(final Transformer<? super RedisConnection, ? extends RedisConnection> fornew) {
        this._fornew = fornew;
    }
    
    public void setDefaultRedisServer(final SocketAddress defaultRedisServerAddr) {
        this._defaultRemoteAddress = defaultRedisServerAddr;
    }
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private volatile SocketAddress _defaultRemoteAddress;
    private volatile Transformer<? super RedisConnection, ? extends RedisConnection> _fornew = null;
}
