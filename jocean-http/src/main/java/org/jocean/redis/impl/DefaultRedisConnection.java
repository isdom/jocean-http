/**
 * 
 */
package org.jocean.redis.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultRedisConnection 
    implements RedisConnection, Comparable<DefaultRedisConnection>  {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultRedisConnection.class);
    
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

    private final InterfaceSelector _selector = new InterfaceSelector();
    
    @SafeVarargs
    DefaultRedisConnection(
        final Channel channel, 
        final Action1<RedisConnection> ... onTerminates) {
        
        this._channel = channel;
        this._terminateAwareSupport = 
            new TerminateAwareSupport<RedisConnection>(this._selector);
        
        RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        fireClosed(new TransportException("channelInactive of " + channel));
                    }});
        
        this._op = this._selector.build(Op.class, OP_ACTIVE, OP_UNACTIVE);
        
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
    
    public boolean isTransacting() {
        return 1 == transactingUpdater.get(this);
    }
    
    @Override
    public Observable<? extends RedisMessage> defineInteraction(
            final Observable<? extends RedisMessage> request) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<RedisMessage>() {
            @Override
            public void call(final Subscriber<? super RedisMessage> subscriber) {
                _op.onSubscribeResponse(DefaultRedisConnection.this, request, subscriber);
            }});
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
            ((DefaultRedisConnection)args[0]).doClose((Throwable)args[1]);
        }};
        
    private void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private void doClose(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active redis connection[channel: {}] by {}", 
                    this._channel, 
                    ExceptionUtils.exception2detail(e));
        }
        
        //  fire all pending subscribers onError with unactived exception
        if (null != this._respObserverHandler) {
            RxNettys.actionToRemoveHandler(this._channel, this._respObserverHandler).call();
        }
        
        // notify response Subscriber with error
        @SuppressWarnings("unchecked")
        final Subscriber<? super RedisMessage> respSubscriber = respSubscriberUpdater.getAndSet(this, null);
        if (null != respSubscriber
           && !respSubscriber.isUnsubscribed()) {
            respSubscriber.onError(e);
        }
        
        if (null != this._reqSubscription
            && !this._reqSubscription.isUnsubscribed()) {
            this._reqSubscription.unsubscribe();
        }
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private final Op _op;
    
    protected interface Op {
        public void onSubscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber);
        
        public void responseOnCompleted(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber);
        
        public void responseOnError(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final Throwable e);
        
        public void responseOnNext(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final RedisMessage msg);
        
        public void doOnUnsubscribeResponse(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber);
        
        public void requestOnNext(
                final DefaultRedisConnection connection,
                final RedisMessage msg);
    }
    
    private static final Op OP_ACTIVE = new Op() {
        @Override
        public void onSubscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber) {
            connection.onSubscribeResponse(request, subscriber);
        }
        
        @Override
        public void responseOnCompleted(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber) {
            connection.responseOnCompleted(subscriber);
        }
        
        @Override
        public void responseOnError(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final Throwable e) {
            connection.responseOnError(subscriber, e);
        }
        
        @Override
        public void responseOnNext(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final RedisMessage msg) {
            connection.responseOnNext(subscriber, msg);
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber) {
            connection.doOnUnsubscribeResponse(subscriber);
        }
        
        @Override
        public void requestOnNext(
                final DefaultRedisConnection connection,
                final RedisMessage msg) {
            connection.requestOnNext(msg);
        }
    };
    
    private static final Op OP_UNACTIVE = new Op() {
        @Override
        public void onSubscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber) {
            subscriber.onError(new RuntimeException("redis connection unactive."));
        }
        
        @Override
        public void responseOnCompleted(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber) {
        }
        
        @Override
        public void responseOnError(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final Throwable e) {
        }
        
        @Override
        public void responseOnNext(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber,
                final RedisMessage msg) {
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultRedisConnection connection,
                final Subscriber<? super RedisMessage> subscriber) {
        }

        @Override
        public void requestOnNext(
                final DefaultRedisConnection connection,
                final RedisMessage msg) {
        }
    };
    
    private volatile Subscription _reqSubscription;
    private volatile ChannelHandler _respObserverHandler;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultRedisConnection, Subscriber> respSubscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultRedisConnection.class, Subscriber.class, "_respSubscriber");
    
    @SuppressWarnings("unused")
    private volatile Subscriber<? super RedisMessage> _respSubscriber;
    
    private static final AtomicIntegerFieldUpdater<DefaultRedisConnection> transactingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRedisConnection.class, "_isTransacting");
    
    @SuppressWarnings("unused")
    private volatile int _isTransacting = 0;

    private final TerminateAwareSupport<RedisConnection> _terminateAwareSupport;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    
    private void onSubscribeResponse(
            final Observable<? extends RedisMessage> request,
            final Subscriber<? super RedisMessage> subscriber) {
        if (subscriber.isUnsubscribed()) {
            LOG.info("response subscriber ({}) has been unsubscribed, ignore", 
                    subscriber);
            return;
        }
        if (respSubscriberUpdater.compareAndSet(this, null, subscriber)) {
            // _respSubscriber field set to subscriber
            this._respObserverHandler = buildObserverHandler(buildHookedRespSubscriber(subscriber));
            this._channel.pipeline().addLast(this._respObserverHandler);
            
            this._reqSubscription = request.subscribe(new Observer<RedisMessage>() {
                    @Override
                    public void onCompleted() {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("request {} invoke onCompleted for connection: {}",
                                request, DefaultRedisConnection.this);
                        }
                    }

                    @Override
                    public void onError(final Throwable e) {
                        LOG.warn("request {} invoke onError with ({}), try close connection: {}",
                                request, ExceptionUtils.exception2detail(e), DefaultRedisConnection.this);
                        fireClosed(e);
                    }

                    @Override
                    public void onNext(final RedisMessage reqmsg) {
                        _op.requestOnNext(DefaultRedisConnection.this, reqmsg);
                    }});
            
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _op.doOnUnsubscribeResponse(DefaultRedisConnection.this, subscriber);
                }}));
        } else {
            // _respSubscriber field has already setted
            subscriber.onError(new RuntimeException("response subscriber already setted."));
        }
    }

    private Subscriber<RedisMessage> buildHookedRespSubscriber(
            final Subscriber<? super RedisMessage> subscriber) {
        return new Subscriber<RedisMessage>(subscriber) {
            @Override
            public String toString() {
                return "hookedSubscriber: redis connection " + _channel;
            }

            @Override
            public void onCompleted() {
                // hook onCompleted
                _op.responseOnCompleted(DefaultRedisConnection.this, subscriber);
            }

            @Override
            public void onError(final Throwable e) {
                // hook onError
                _op.responseOnError(DefaultRedisConnection.this, subscriber, e);
            }

            @Override
            public void onNext(final RedisMessage respmsg) {
                _op.responseOnNext(DefaultRedisConnection.this, subscriber, respmsg);
            }};
    }

    private void requestOnNext(final RedisMessage reqmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending redis request msg {}", reqmsg);
        }
        // set in transacting flag
        setTransacting();
        this._channel.writeAndFlush(ReferenceCountUtil.retain(reqmsg))
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send redis request msg {} success.", reqmsg);
                    }
                } else {
                    LOG.warn("exception when send redis req: {}, detail: {}",
                            reqmsg, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send reqmsg error", future.cause()));
                }
            }});
    }

    private void responseOnNext(
            final Subscriber<? super RedisMessage> subscriber,
            final RedisMessage respmsg) {
        subscriber.onNext(respmsg);
    }

    private void responseOnError(
            final Subscriber<? super RedisMessage> subscriber,
            final Throwable e) {
        resetRespSubscriber(subscriber);
        subscriber.onError(e);
    }

    private void responseOnCompleted(
            final Subscriber<? super RedisMessage> subscriber) {
        resetRespSubscriber(subscriber);
        clearTransacting();
        subscriber.onCompleted();
    }

    private void doOnUnsubscribeResponse(
            final Subscriber<? super RedisMessage> subscriber) {
        if (resetRespSubscriber(subscriber)) {
            // unsubscribe before OnCompleted or OnError
            fireClosed(new RuntimeException("unsubscribe response"));
        }
    }

    private boolean resetRespSubscriber(
            final Subscriber<? super RedisMessage> subscriber) {
        return respSubscriberUpdater.compareAndSet(this, subscriber, null);
    }
    
    private void clearTransacting() {
        transactingUpdater.compareAndSet(DefaultRedisConnection.this, 1, 0);
    }

    private void setTransacting() {
        transactingUpdater.compareAndSet(DefaultRedisConnection.this, 0, 1);
    }

    private static ChannelHandler buildObserverHandler(final Observer<? super RedisMessage> observer) {
        return new SimpleChannelInboundHandler<RedisMessage>(false) {
            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx,
                    final Throwable cause) throws Exception {
                LOG.warn("exceptionCaught at channel({})/handler({}), detail:{}, and call ({}).onError with TransportException.", 
                        ctx.channel(), ctx.name(),
                        ExceptionUtils.exception2detail(cause), 
                        observer);
                observer.onError(new TransportException("exceptionCaught", cause));
                ctx.close();
            }

            @Override
            public void channelInactive(final ChannelHandlerContext ctx)
                    throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("channel({})/handler({}): channelInactive and call ({}).onError with TransportException.", 
                            ctx.channel(), ctx.name(), observer);
                }
                observer.onError(new TransportException("channelInactive"));
                ctx.close();
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext ctx,
                    final RedisMessage msg) throws Exception {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call ({}).onNext with msg({}).", 
                                ctx.channel(), ctx.name(), observer, msg);
                    }

                    try {
                        observer.onNext(msg);
                    } catch (Exception e) {
                        LOG.warn("exception when invoke onNext for channel({})/msg ({}), detail: {}.", 
                                ctx.channel(), msg, ExceptionUtils.exception2detail(e));
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
                
                // one req/one resp
                //  remove handler itself
                RxNettys.actionToRemoveHandler(ctx.channel(), this).call();
                try {
                    observer.onCompleted();
                } catch (Exception e) {
                    LOG.warn("exception when invoke onCompleted for channel({}), detail: {}.", 
                            ctx.channel(), ExceptionUtils.exception2detail(e));
                }
            }
        };
    }
}
