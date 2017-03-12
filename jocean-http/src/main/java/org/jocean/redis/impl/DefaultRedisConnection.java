/**
 * 
 */
package org.jocean.redis.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.TransportException;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.jocean.redis.RedisClient.RedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;

/**
 * @author isdom
 *
 */
class DefaultRedisConnection 
    implements RedisConnection, Comparable<DefaultRedisConnection>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultRedisConnection o) {
        return this._id - o._id;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this._id;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefaultRedisConnection other = (DefaultRedisConnection) obj;
        if (this._id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultRedisConnection [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultRedisConnection.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    
    @SafeVarargs
    DefaultRedisConnection(
        final Channel channel, 
        final Action1<RedisConnection> ... onTerminates) {
        
        this._channel = channel;
        this._terminateAwareSupport = 
            new TerminateAwareSupport<RedisConnection>(_selector);
        
        RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        fireClosed(new TransportException("channelInactive of " + channel));
                    }});
        
        for (Action1<RedisConnection> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        if (!channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        }
    }

    public Channel channel() {
        return this._channel;
    }
    
    @Override
    public Observable<? extends RedisMessage> defineInteraction(
            final Observable<? extends RedisMessage> request) {
        return recvResponse(request, this._channel);
    }

    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    @Override
    public void close() {
        fireClosed(new RuntimeException("call close()"));
    }

    @Override
    public Action1<Action0> onTerminate() {
        return  this._terminateAwareSupport.onTerminate(this);
    }

    @Override
    public Action1<Action1<RedisConnection>> onTerminateOf() {
        return  this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(final Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    @Override
    public Action0 doOnTerminate(final Action1<RedisConnection> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultRedisConnection)args[0]).fireClosed0((Throwable)args[1]);
        }};
        
    private void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private void fireClosed0(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active redis connection[channel: {}] by {}", 
                    this._channel, 
                    ExceptionUtils.exception2detail(e));
        }
        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private final TerminateAwareSupport<RedisConnection> _terminateAwareSupport;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    
    private Observable<? extends RedisMessage> recvResponse(
            final Observable<? extends RedisMessage> request, 
            final Channel channel) {
        return Observable.create(new Observable.OnSubscribe<RedisMessage>() {
            @Override
            public void call(final Subscriber<? super RedisMessage> subscriber) {
                //  TODO: now just add business handler at last
                final ChannelHandler handler = buildSubscribHandler(subscriber);
                channel.pipeline().addLast(handler);
                doOnTerminate(RxNettys.actionToRemoveHandler(channel, handler));
                
                request.doOnCompleted(flushWhenCompleted(channel))
                .doOnNext(new Action1<RedisMessage>() {
                    @Override
                    public void call(final RedisMessage reqmsg) {
                        final ChannelFuture future = channel.write(ReferenceCountUtil.retain(reqmsg));
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("send redis request msg :{}", reqmsg);
                        }
                    }})
                .subscribe();
            }});
    }
    
    private Action0 flushWhenCompleted(final Channel channel) {
        return new Action0() {
            @Override
            public void call() {
                channel.flush();
            }};
    }
    
//    private Func1<RedisMessage, Observable<? extends RedisMessage>> sendRequest(
//            final Channel channel) {
//        return new Func1<RedisMessage, Observable<? extends RedisMessage>>() {
//            @Override
//            public Observable<? extends RedisMessage> call(final RedisMessage reqmsg) {
//                final ChannelFuture future = channel.write(ReferenceCountUtil.retain(reqmsg));
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("send redis request msg :{}", reqmsg);
//                }
//                RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
//                return RxNettys.observableFromFuture(future);
//            }};
//    }
    
    private static ChannelHandler buildSubscribHandler(final Subscriber<? super RedisMessage> subscriber) {
        return new SimpleChannelInboundHandler<RedisMessage>(false) {
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
                ctx.close();
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext ctx,
                    final RedisMessage msg) throws Exception {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call ({}).onNext with msg({}).", 
                                ctx.channel(), ctx.name(), subscriber, msg);
                    }
                    
                    if (!subscriber.isUnsubscribed()) {
                        try {
                            subscriber.onNext(msg);
                        } catch (Exception e) {
                            LOG.warn("exception when invoke onNext for channel({})/msg ({}), detail: {}.", 
                                    ctx.channel(), msg, ExceptionUtils.exception2detail(e));
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
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
        };
    }
}
