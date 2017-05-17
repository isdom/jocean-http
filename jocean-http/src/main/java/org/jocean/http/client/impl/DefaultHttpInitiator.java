/**
 * 
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.ReadPolicy;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.Nettys;
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
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observer;
import rx.Single;
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
class DefaultHttpInitiator
    implements HttpInitiator, Comparable<DefaultHttpInitiator>{
    
    private static final Action1<Object> DECLARESUPPORTCOMPRESS = new Action1<Object>() {
        @Override
        public void call(final Object msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest request = (HttpRequest)msg;
                request.headers().add(
                    HttpHeaderNames.ACCEPT_ENCODING, 
                    HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);
            }
        }};
        
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultHttpInitiator o) {
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
        DefaultHttpInitiator other = (DefaultHttpInitiator) obj;
        if (this._id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpInitiator [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", isActive=").append(isActive())
                .append(", transactionStatus=").append(transactionStatusAsString())
                .append(", isKeepAlive=").append(isKeepAlive())
                .append(", isRequestCompleted=").append(_isRequestCompleted)
                .append(", reqSubscription=").append(_reqSubscription)
                .append(", respSubscriber=").append(_respSubscriber)
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInitiator.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();

    @SafeVarargs
    DefaultHttpInitiator(
        final Channel channel, 
        final Action1<HttpInitiator> ... onTerminates) {
        
        this._channel = channel;
        this._terminateAwareSupport = 
            new TerminateAwareSupport<HttpInitiator>(this._selector);
        
//        this._trafficCounter = RxNettys.applyToChannelWithUninstall(channel, 
//                onTerminate(), 
//                APPLY.TRAFFICCOUNTER);
        
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
                        onChannelInactive();
                    }});
        
        RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.ON_CHANNEL_READCOMPLETE,
                new Action0() {
                    @Override
                    public void call() {
                        onReadComplete();
                    }});
        
        this._op = this._selector.build(Op.class, OP_ACTIVE, OP_UNACTIVE);
        
        for (Action1<HttpInitiator> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        if (!channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        }
    }

    private void onChannelInactive() {
        if (inTransacting()) {
            fireClosed(new TransportException("channelInactive of " + this._channel));
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel inactive after transaction finished, MAYBE Connection: close");
            }
        }
    }

    private void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (inTransacting()) {
            final Single<?> when = whenToRead();
            if (null != when) {
                when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        _op.readMessage(DefaultHttpInitiator.this);
                    }});
            } else {
                //  perform read at once
                _op.readMessage(DefaultHttpInitiator.this);
            }
        }
    }

    private Single<?> whenToRead() {
        final ReadPolicy readPolicy = this._readPolicy;
        return null != readPolicy ? readPolicy.whenToRead(this) : null;
    }

    @Override
    public void setReadPolicy(final ReadPolicy readPolicy) {
        this._readPolicy = readPolicy;
    }
    
    @Override
    public void setFlushPerWrite(final boolean isFlushPerWrite) {
        this._isFlushPerWrite = isFlushPerWrite;
    }
    
    @Override
    public void setWriteBufferWaterMark(final int low, final int high) {
        this._op.setWriteBufferWaterMark(this, low, high);
    }
    
    @Override
    public void setOnSended(final Action1<Object> onSended) {
        this._onSended = onSended;
    }
    
    @Override
    public Observable<? extends HttpObject> defineInteraction(
            final Observable<? extends Object> request) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                _op.subscribeResponse(DefaultHttpInitiator.this, request, subscriber);
            }});
    }
    
    @Override
    public <T extends ChannelHandler> T enable(final APPLY apply, final Object... args) {
        return _op.enable(this, apply, args);
    }
    
    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    boolean inTransacting() {
        return transactionStatus() > STATUS_NOTSTART;
    }
    
    boolean isKeepAlive() {
        return this._isKeepAlive;
    }
    
    @Override
    public long unreadDurationInMs() {
        final long begin = this._unreadBegin;
        return 0 == begin ? 0 : System.currentTimeMillis() - begin;
    }
    
    @Override
    public long readingDurationInMS() {
        return Math.max(System.currentTimeMillis() - readBeginUpdater.get(this), 1L);
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
    public Action1<Action1<HttpInitiator>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
                
    @Override
    public Action0 doOnTerminate(final Action1<HttpInitiator> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultHttpInitiator)args[0]).doClosed((Throwable)args[1]);
        }};
        
    private void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private void doClosed(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active initiator[channel: {}] "
                    + "with isRequestCompleted({})"
                    + "/transactionStatus({})"
                    + "/isKeepAlive({}),"
                    + "cause by {}", 
                    this._channel, 
                    this._isRequestCompleted, 
                    this.transactionStatusAsString(),
                    this.isKeepAlive(),
                    ExceptionUtils.exception2detail(e));
        }
        
        removeRespHandler();
        
        // notify response Subscriber with error
        releaseRespWithError(e);
        
        unsubscribeRequest();
        
        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private String transactionStatusAsString() {
        switch(transactionStatus()) {
        case STATUS_NOTSTART:
            return "NOTSTART";
        case STATUS_SEND:
            return "SEND";
        case STATUS_RECV:
            return "RECV";
        default:
            return "UNKNOWN";
        }
    }

    private final Op _op;
    
    protected interface Op {
        public <T extends ChannelHandler> T enable(
                final DefaultHttpInitiator initiator, 
                final APPLY apply, final Object... args);

        public void subscribeResponse(
                final DefaultHttpInitiator initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber);
        
        public void responseOnNext(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg);
        
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber);
        
        public void requestOnNext(
                final DefaultHttpInitiator initiator,
                final Object msg);

        public void requestOnCompleted(
                final DefaultHttpInitiator initiator);
        
        public void readMessage(final DefaultHttpInitiator initiator);

        public void setWriteBufferWaterMark(final DefaultHttpInitiator initiator,
                final int low, final int high);
    }
    
    private static final Op OP_ACTIVE = new Op() {
        public <T extends ChannelHandler> T enable(
                final DefaultHttpInitiator initiator, 
                final APPLY apply, final Object... args) {
            return RxNettys.applyToChannelWithUninstall(initiator._channel, 
                    initiator.onTerminate(), apply, args);
        }
        
        @Override
        public void subscribeResponse(
                final DefaultHttpInitiator initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber) {
            initiator.subscribeResponse(request, subscriber);
        }
        
        @Override
        public void responseOnNext(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg) {
            initiator.responseOnNext(subscriber, msg);
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber) {
            initiator.doOnUnsubscribeResponse(subscriber);
        }
        
        @Override
        public void requestOnNext(
                final DefaultHttpInitiator initiator,
                final Object msg) {
            initiator.requestOnNext(msg);
        }
        
        @Override
        public void requestOnCompleted(
                final DefaultHttpInitiator initiator) {
            initiator.requestOnCompleted();
        }
        
        @Override
        public void readMessage(final DefaultHttpInitiator initiator) {
            initiator.readMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final DefaultHttpInitiator initiator,
                final int low, final int high) {
            initiator._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
        }
    };
    
    private static final Op OP_UNACTIVE = new Op() {
        public <T extends ChannelHandler> T enable(
                final DefaultHttpInitiator initiator, 
                final APPLY apply, final Object... args) {
            return null;
        }
        
        @Override
        public void subscribeResponse(
                final DefaultHttpInitiator initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super HttpObject> subscriber) {
            subscriber.onError(new RuntimeException("http connection unactive."));
        }
        
        @Override
        public void responseOnNext(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg) {
            ReferenceCountUtil.release(msg);
        }
        
        @Override
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super HttpObject> subscriber) {
        }

        @Override
        public void requestOnNext(
                final DefaultHttpInitiator initiator,
                final Object msg) {
        }
        
        @Override
        public void requestOnCompleted(
                final DefaultHttpInitiator initiator) {
        }
        
        @Override
        public void readMessage(final DefaultHttpInitiator initiator) {
        }

        @Override
        public void setWriteBufferWaterMark(DefaultHttpInitiator initiator,
                int low, int high) {
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
                    _op.responseOnNext(DefaultHttpInitiator.this, subscriber, respmsg);
                }};
            Nettys.applyHandler(APPLY.ON_MESSAGE, this._channel.pipeline(), handler);
            respHandlerUpdater.set(this, handler);
            reqSubscriptionUpdater.set(this,  wrapRequest(request).subscribe(buildRequestObserver()));
            
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _op.doOnUnsubscribeResponse(DefaultHttpInitiator.this, subscriber);
                }}));
        } else {
            // _respSubscriber field has already setted
            subscriber.onError(new RuntimeException("response subscriber already setted."));
        }
    }

    private Observable<? extends Object> wrapRequest(
            final Observable<? extends Object> request) {
        if (Nettys.isSupportCompress(this._channel)) {
            return request.doOnNext(DECLARESUPPORTCOMPRESS);
        } else {
            return request;
        }
    }

    private void readMessage() {
        if (inTransacting()) {
            this._channel.read();
            this._unreadBegin = 0;
            readBeginUpdater.compareAndSet(this, 0, System.currentTimeMillis());
        }
    }

    private Observer<Object> buildRequestObserver() {
        return new Observer<Object>() {
                @Override
                public void onCompleted() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("request invoke onCompleted for connection: {}", DefaultHttpInitiator.this);
                    }
                    _op.requestOnCompleted(DefaultHttpInitiator.this);
                }

                @Override
                public void onError(final Throwable e) {
                    LOG.warn("request invoke onError with ({}), try close connection: {}",
                            ExceptionUtils.exception2detail(e), DefaultHttpInitiator.this);
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object reqmsg) {
                    _op.requestOnNext(DefaultHttpInitiator.this, reqmsg);
                }};
    }

    private void requestOnCompleted() {
        // force flush for _isFlushPerWrite = false
        this._channel.flush();
        this._isRequestCompleted = true;
        this.readMessage();
    }

    private void requestOnNext(final Object reqmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http request msg {}", reqmsg);
        }
        // set in transacting flag
        markStartSending();
        
        if (reqmsg instanceof HttpRequest) {
            onRequest((HttpRequest)reqmsg);
        }
        
        sendOutbound(reqmsg)
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send http request msg {} success.", reqmsg);
                    }
                    onOutboundMsgSended(reqmsg);
                } else {
                    LOG.warn("exception when send http req: {}, detail: {}",
                            reqmsg, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send reqmsg error", future.cause()));
                }
            }});
    }

    private void onOutboundMsgSended(final Object reqmsg) {
        final Action1<Object> onSended = this._onSended;
        
        if (null != onSended) {
            try {
                onSended.call(reqmsg);
            } catch (Exception e) {
                LOG.warn("exception when invoke onSended({}) with msg({}), detail: {}",
                    onSended, 
                    reqmsg, 
                    ExceptionUtils.exception2detail(e));
            }
        }
    }

    private ChannelFuture sendOutbound(final Object reqmsg) {
        return this._isFlushPerWrite
                ? this._channel.writeAndFlush(ReferenceCountUtil.retain(reqmsg))
                : this._channel.write(ReferenceCountUtil.retain(reqmsg));
    }

    private void onRequest(final HttpRequest req) {
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
    }

    private void responseOnNext(
            final Subscriber<? super HttpObject> subscriber,
            final HttpObject respmsg) {
        markStartRecving();
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
                    endTransaction();
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
    
    private void markStartSending() {
        transactionUpdater.compareAndSet(this, STATUS_NOTSTART, STATUS_SEND);
    }
    
    private void markStartRecving() {
        transactionUpdater.compareAndSet(this, STATUS_SEND, STATUS_RECV);
    }
    
    private void endTransaction() {
        transactionUpdater.compareAndSet(this, STATUS_RECV, STATUS_NOTSTART);
    }
    
    private int transactionStatus() {
        return transactionUpdater.get(this);
    }

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator, Subscriber> respSubscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator.class, Subscriber.class, "_respSubscriber");
    
    private volatile Subscriber<? super HttpObject> _respSubscriber;
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator, ChannelHandler> respHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator.class, ChannelHandler.class, "_respHandler");
    
    @SuppressWarnings("unused")
    private volatile ChannelHandler _respHandler;
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpInitiator, Subscription> reqSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpInitiator.class, Subscription.class, "_reqSubscription");
    
    private volatile Subscription _reqSubscription;
    
    private static final AtomicIntegerFieldUpdater<DefaultHttpInitiator> transactionUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpInitiator.class, "_transactionStatus");
    
    private static final int STATUS_NOTSTART = 0;
    private static final int STATUS_SEND = 1;
    private static final int STATUS_RECV = 2;
    
    @SuppressWarnings("unused")
    private volatile int _transactionStatus = STATUS_NOTSTART;
    
    private volatile boolean _isKeepAlive = true;
    
    private static final AtomicLongFieldUpdater<DefaultHttpInitiator> readBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultHttpInitiator.class, "_readBegin");
    
    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;
    
    private volatile long _unreadBegin = 0;
    
    private volatile Action1<Object> _onSended = null;

    private volatile boolean _isFlushPerWrite = false;
    private volatile boolean _isRequestCompleted = false;
    private volatile ReadPolicy _readPolicy = null;
    
    private final TerminateAwareSupport<HttpInitiator> _terminateAwareSupport;

    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
}
