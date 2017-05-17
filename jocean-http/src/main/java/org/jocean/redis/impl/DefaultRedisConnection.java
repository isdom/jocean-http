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
import org.jocean.http.util.Nettys;
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
                .append(", isTransacting=").append(isTransacting())
                .append(", reqSubscription=").append(_reqSubscription)
                .append(", respSubscriber=").append(_respSubscriber)
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
                APPLY.ON_EXCEPTION_CAUGHT,
                new Action1<Throwable>() {
                    @Override
                    public void call(final Throwable cause) {
                        fireClosed(cause);
                    }});
        
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

    Channel channel() {
        return this._channel;
    }
    
    boolean isTransacting() {
        return 1 == transactingUpdater.get(this);
    }
    
    @Override
    public Observable<? extends RedisMessage> defineInteraction(
            final Observable<? extends RedisMessage> request) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<RedisMessage>() {
            @Override
            public void call(final Subscriber<? super RedisMessage> subscriber) {
                _op.subscribeResponse(DefaultRedisConnection.this, request, subscriber);
            }});
    }

    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    @Override
    public Action0 closer() {
        return new Action0() {
            @Override
            public void call() {
                close();
            }};
    }
    
    @Override
    public void close() {
        fireClosed(new RuntimeException("close()"));
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
        
        removeRespHandler();
        
        // notify response Subscriber with error
        releaseRespWithError(e);
        
        unsubscribeRequest();
        
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private void removeRespHandler() {
        final ChannelHandler handler = respHandlerUpdater.getAndSet(this, null);
        if (null != handler) {
            RxNettys.actionToRemoveHandler(this._channel, handler).call();
        }
    }

    private void releaseRespWithError(final Throwable e) {
        @SuppressWarnings("unchecked")
        final Subscriber<? super RedisMessage> respSubscriber = respSubscriberUpdater.getAndSet(this, null);
        if (null != respSubscriber
           && !respSubscriber.isUnsubscribed()) {
            try {
                respSubscriber.onError(e);
            } catch (Exception error) {
                LOG.warn("exception when invoke {}.onError, detail: {}",
                    respSubscriber, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private void unsubscribeRequest() {
        final Subscription reqSubscription = reqSubscriptionUpdater.getAndSet(this, null);
        if (null != reqSubscription
            && !reqSubscription.isUnsubscribed()) {
            reqSubscription.unsubscribe();
        }
    }

    private final Op _op;
    
    protected interface Op {
        public void subscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber);
        
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
        public void subscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber) {
            connection.subscribeResponse(request, subscriber);
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
        public void subscribeResponse(
                final DefaultRedisConnection connection,
                final Observable<? extends RedisMessage> request,
                final Subscriber<? super RedisMessage> subscriber) {
            subscriber.onError(new RuntimeException("redis connection unactive."));
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
    
    private static final AtomicReferenceFieldUpdater<DefaultRedisConnection, ChannelHandler> respHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultRedisConnection.class, ChannelHandler.class, "_respHandler");
    
    @SuppressWarnings("unused")
    private volatile ChannelHandler _respHandler;
    
    private static final AtomicReferenceFieldUpdater<DefaultRedisConnection, Subscription> reqSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultRedisConnection.class, Subscription.class, "_reqSubscription");
    
    private volatile Subscription _reqSubscription;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultRedisConnection, Subscriber> respSubscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultRedisConnection.class, Subscriber.class, "_respSubscriber");
    
    private volatile Subscriber<? super RedisMessage> _respSubscriber;
    
    private static final AtomicIntegerFieldUpdater<DefaultRedisConnection> transactingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultRedisConnection.class, "_isTransacting");
    
    @SuppressWarnings("unused")
    private volatile int _isTransacting = 0;

    private final TerminateAwareSupport<RedisConnection> _terminateAwareSupport;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    
    private void subscribeResponse(
            final Observable<? extends RedisMessage> request,
            final Subscriber<? super RedisMessage> subscriber) {
        if (subscriber.isUnsubscribed()) {
            LOG.info("response subscriber ({}) has been unsubscribed, ignore", 
                    subscriber);
            return;
        }
        if (holdRespSubscriber(subscriber)) {
            // _respSubscriber field set to subscriber
            final ChannelHandler handler = new SimpleChannelInboundHandler<RedisMessage>() {
                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final RedisMessage respmsg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call with msg({}).",
                            ctx.channel(), ctx.name(), respmsg);
                    }
                    _op.responseOnNext(DefaultRedisConnection.this, subscriber, respmsg);
                }};
            Nettys.applyHandler(APPLY.ON_MESSAGE, this._channel.pipeline(), handler);
            respHandlerUpdater.set(DefaultRedisConnection.this, handler);
            
            reqSubscriptionUpdater.set(DefaultRedisConnection.this, 
                    request.subscribe(buildRequestObserver(request)));
            
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

    private Observer<RedisMessage> buildRequestObserver(
            final Observable<? extends RedisMessage> request) {
        return new Observer<RedisMessage>() {
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
        if (unholdRespSubscriber(subscriber)) {
            removeRespHandler();
            clearTransacting();
            try {
                subscriber.onNext(respmsg);
            } finally {
                subscriber.onCompleted();
            }
        }
    }

    private void doOnUnsubscribeResponse(
            final Subscriber<? super RedisMessage> subscriber) {
        if (unholdRespSubscriber(subscriber)) {
            removeRespHandler();
            // unsubscribe before OnCompleted or OnError
            fireClosed(new RuntimeException("unsubscribe response"));
        }
    }

    private boolean holdRespSubscriber(
            final Subscriber<? super RedisMessage> subscriber) {
        return respSubscriberUpdater.compareAndSet(this, null, subscriber);
    }

    private boolean unholdRespSubscriber(
            final Subscriber<? super RedisMessage> subscriber) {
        return respSubscriberUpdater.compareAndSet(this, subscriber, null);
    }
    
    private void clearTransacting() {
        transactingUpdater.compareAndSet(DefaultRedisConnection.this, 1, 0);
    }

    private void setTransacting() {
        // TODO, using this field as counter for req redis count & resp redis count
        transactingUpdater.compareAndSet(DefaultRedisConnection.this, 0, 1);
    }
}
