/**
 * 
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient.HttpInitiator0;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
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
class DefaultHttpInitiator0
    implements HttpInitiator0, Comparable<DefaultHttpInitiator0>{
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultHttpInitiator0 o) {
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
        DefaultHttpInitiator0 other = (DefaultHttpInitiator0) obj;
        if (this._id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpInitiator [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", isInboundReceived=").append(_isInboundReceived.get())
                .append(", requestMethod=").append(_requestMethod)
                .append(", requestUri=").append(_requestUri)
                .append(", isInboundCompleted=").append(_isInboundCompleted.get())
                .append(", isOutboundSetted=").append(_isOutboundSetted.get())
                .append(", isOutboundSended=").append(_isOutboundSended.get())
                .append(", isOutboundCompleted=").append(_isOutboundCompleted.get())
                .append(", isKeepAlive=").append(_isKeepAlive.get())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInitiator0.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    
    @SafeVarargs
    DefaultHttpInitiator0(
        final Channel channel, 
        final Action1<HttpInitiator0> ... onTerminates) {
        
        this._channel = channel;
        this._terminateAwareSupport = 
            new TerminateAwareSupport<HttpInitiator0>(this._selector);
        
        this._trafficCounter = RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.TRAFFICCOUNTER);
        
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
        
        for (Action1<HttpInitiator0> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        if (!channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        }
    }

    @Override
    public Observable<? extends HttpObject> defineInteraction(
            Observable<? extends Object> request) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                _op.subscribeResponse(DefaultHttpInitiator0.this, request, subscriber);
            }});
    }
    
    public void setApplyToRequest(final ApplyToRequest applyToRequest) {
        this._applyToRequest = applyToRequest;
    }

    @Override
    public TrafficCounter trafficCounter() {
        return this._trafficCounter;
    }
    
    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    boolean isTransacting() {
        return 1 == transactingUpdater.get(this);
    }
    
    boolean isEndedWithKeepAlive() {
        return (this._isInboundCompleted.get() 
            && this._isOutboundCompleted.get()
            && this._isKeepAlive.get());
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

//    @Override
    public Object transport() {
        return this._channel;
    }
    
    Channel channel() {
        return this._channel;
    }
    
    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate(this);
    }
            
    @Override
    public Action1<Action1<HttpInitiator0>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
                
    @Override
    public Action0 doOnTerminate(final Action1<HttpInitiator0> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultHttpInitiator0)args[0]).doClosed((Throwable)args[1]);
        }};
        
    private void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private void doClosed(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active initiator[channel: {}] with isOutboundCompleted({})/isEndedWithKeepAlive({}), by {}", 
                    this._channel, this._isOutboundCompleted.get(), this.isEndedWithKeepAlive(),
                    ExceptionUtils.exception2detail(e));
        }
        
        removeRespHandler();
        
        // notify response Subscriber with error
        releaseRespWithError(e);
        
        unsubscribeRequest();
        
        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private final Op _op;
    
    protected interface Op {
        public void subscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber);
        
        public void responseOnNext(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg);
        
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber);
        
        public void requestOnNext(
                final DefaultHttpInitiator0 initiator,
                final Object msg);
    }
    
    private static final Op OP_ACTIVE = new Op() {
        @Override
        public void subscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber) {
            initiator.subscribeResponse(request, subscriber);
        }
        
        @Override
        public void responseOnNext(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg) {
            initiator.responseOnNext(subscriber, msg);
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber) {
            initiator.doOnUnsubscribeResponse(subscriber);
        }
        
        @Override
        public void requestOnNext(
                final DefaultHttpInitiator0 initiator,
                final Object msg) {
            initiator.requestOnNext(msg);
        }
    };
    
    private static final Op OP_UNACTIVE = new Op() {
        @Override
        public void subscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber) {
            subscriber.onError(new RuntimeException("http connection unactive."));
        }
        
        @Override
        public void responseOnNext(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg) {
            ReferenceCountUtil.release(msg);
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator0 initiator,
                final Subscriber<? super HttpObject> subscriber) {
        }

        @Override
        public void requestOnNext(
                final DefaultHttpInitiator0 initiator,
                final Object msg) {
        }
    };
    
    private void subscribeResponse(
            final Observable<? extends Object> request,
            final Subscriber<? super HttpObject> subscriber) {
        if (subscriber.isUnsubscribed()) {
            LOG.info("response subscriber ({}) has been unsubscribed, ignore", 
                    subscriber);
            return;
        }
        if (holdRespSubscriber(subscriber)) {
            // _respSubscriber field set to subscriber
            final ChannelHandler handler = new SimpleChannelInboundHandler<HttpObject>(false) {
                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject respmsg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call with msg({}).",
                            ctx.channel(), ctx.name(), respmsg);
                    }
                    // TODO, add refcnt and exec on the eventloop thread on next round
                    _op.responseOnNext(DefaultHttpInitiator0.this, subscriber, respmsg);
                }};
            APPLY.ON_MESSAGE.applyTo(this._channel.pipeline(), handler);
            respHandlerUpdater.set(DefaultHttpInitiator0.this, handler);
            
            reqSubscriptionUpdater.set(DefaultHttpInitiator0.this, 
                    request.subscribe(buildRequestObserver(request)));
            
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _op.doOnUnsubscribeResponse(DefaultHttpInitiator0.this, subscriber);
                }}));
        } else {
            // _respSubscriber field has already setted
            subscriber.onError(new RuntimeException("response subscriber already setted."));
        }
    }

    private Observer<Object> buildRequestObserver(
            final Observable<? extends Object> request) {
        return new Observer<Object>() {
                @Override
                public void onCompleted() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("request {} invoke onCompleted for connection: {}",
                            request, DefaultHttpInitiator0.this);
                    }
                    _isOutboundCompleted.compareAndSet(false, true);
//                  _inboundSupport.readMessage();
                }

                @Override
                public void onError(final Throwable e) {
                    LOG.warn("request {} invoke onError with ({}), try close connection: {}",
                            request, ExceptionUtils.exception2detail(e), DefaultHttpInitiator0.this);
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object reqmsg) {
                    _op.requestOnNext(DefaultHttpInitiator0.this, reqmsg);
                }};
    }

    private void requestOnNext(final Object reqmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http request msg {}", reqmsg);
        }
        // set in transacting flag
        setTransacting();
        
        if (reqmsg instanceof HttpRequest) {
            onRequest((HttpRequest)reqmsg);
        }
        _isOutboundSended.compareAndSet(false, true);
        
        this._channel.writeAndFlush(ReferenceCountUtil.retain(reqmsg))
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send http request msg {} success.", reqmsg);
                    }
                } else {
                    LOG.warn("exception when send http req: {}, detail: {}",
                            reqmsg, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send reqmsg error", future.cause()));
                }
            }});
    }

    private void onRequest(final HttpRequest req) {
        final ApplyToRequest applyTo = this._applyToRequest;
        if (null != applyTo) {
            try {
                applyTo.call(req);
            } catch (Exception e) {
                LOG.warn("exception when invoke applyToRequest.call, detail: {}",
                  ExceptionUtils.exception2detail(e));
            }
        }
        
        this._requestMethod = req.method().name();
        this._requestUri = req.uri();
        this._isKeepAlive.set(HttpUtil.isKeepAlive(req));
    }

    private void responseOnNext(
            final Subscriber<? super HttpObject> subscriber,
            final HttpObject respmsg) {
        this._isInboundReceived.compareAndSet(false, true);
        try {
            subscriber.onNext(respmsg);
        } finally {
            ReferenceCountUtil.release(respmsg);
            if (respmsg instanceof LastHttpContent) {
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
                if (unholdRespSubscriber(subscriber)) {
                    removeRespHandler();
                    clearTransacting();
                    this._isInboundCompleted.set(true);
                    subscriber.onCompleted();
                }
            }
        }
    }

    private void doOnUnsubscribeResponse(
            final Subscriber<? super HttpObject> subscriber) {
        if (unholdRespSubscriber(subscriber)) {
            removeRespHandler();
            // unsubscribe before OnCompleted or OnError
            fireClosed(new RuntimeException("unsubscribe response"));
        }
    }

    private void removeRespHandler() {
        final ChannelHandler handler = respHandlerUpdater.getAndSet(this, null);
        if (null != handler) {
            RxNettys.actionToRemoveHandler(this._channel, handler).call();
        }
    }

    private void releaseRespWithError(final Throwable e) {
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject> respSubscriber = respSubscriberUpdater.getAndSet(this, null);
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
    
    private boolean holdRespSubscriber(
            final Subscriber<? super HttpObject> subscriber) {
        return respSubscriberUpdater.compareAndSet(this, null, subscriber);
    }

    private boolean unholdRespSubscriber(
            final Subscriber<? super HttpObject> subscriber) {
        return respSubscriberUpdater.compareAndSet(this, subscriber, null);
    }
    
    private void clearTransacting() {
        transactingUpdater.compareAndSet(DefaultHttpInitiator0.this, 1, 0);
    }

    private void setTransacting() {
        transactingUpdater.compareAndSet(DefaultHttpInitiator0.this, 0, 1);
    }
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator0, ChannelHandler> respHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator0.class, ChannelHandler.class, "_respHandler");
    
    @SuppressWarnings("unused")
    private volatile ChannelHandler _respHandler;
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator0, Subscription> reqSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator0.class, Subscription.class, "_reqSubscription");
    
    private volatile Subscription _reqSubscription;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator0, Subscriber> respSubscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator0.class, Subscriber.class, "_respSubscriber");
    
    private volatile Subscriber<? super HttpObject> _respSubscriber;
    
    private static final AtomicIntegerFieldUpdater<DefaultHttpInitiator0> transactingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpInitiator0.class, "_isTransacting");
    
    @SuppressWarnings("unused")
    private volatile int _isTransacting = 0;
    
    private final TerminateAwareSupport<HttpInitiator0> _terminateAwareSupport;
    private volatile ApplyToRequest _applyToRequest;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isInboundReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isInboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSended = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
}
