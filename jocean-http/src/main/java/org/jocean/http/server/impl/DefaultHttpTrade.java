/**
 * 
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.ReadPolicy;
import org.jocean.http.ReadPolicy.Inboundable;
import org.jocean.http.WritePolicy.Outboundable;
import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.WritePolicy;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import rx.Observable;
import rx.Observable.OnSubscribe;
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
class DefaultHttpTrade extends DefaultAttributeMap 
    implements HttpTrade,  Comparable<DefaultHttpTrade> {//, Transformer<Object, Object>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(1);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultHttpTrade o) {
        return this._id - o._id;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + _id;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefaultHttpTrade other = (DefaultHttpTrade) obj;
        if (_id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", requestMethod=").append(this._requestMethod)
                .append(", requestUri=").append(this._requestUri)
                .append(", isKeepAlive=").append(isKeepAlive())
                .append(", transactionStatus=").append(transactionStatus())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    @Override
    public TrafficCounter traffic() {
        return this._traffic;
    }
    
    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }
    
    /* (non-Javadoc)
     * @see IntrafficController#setReadPolicy(org.jocean.http.ReadPolicy)
     */
    @Override
    public void setReadPolicy(final ReadPolicy readPolicy) {
        this._whenToRead = null != readPolicy 
                ? readPolicy.whenToRead(buildInboundable()) 
                : null;
    }

    private Inboundable buildInboundable() {
        return new Inboundable() {
            @Override
            public long durationFromRead() {
                final long begin = _unreadBegin;
                return 0 == begin ? 0 : System.currentTimeMillis() - begin;
            }
            
            @Override
            public long durationFromBegin() {
                return Math.max(System.currentTimeMillis() - readBeginUpdater.get(DefaultHttpTrade.this), 1L);
            }
            
            @Override
            public long inboundBytes() {
                return _traffic.inboundBytes();
            }};
    }
    
    @Override
    public Observable<? extends HttpObject> inbound() {
        return this._cachedInbound;
    }

    @Override
    public HttpMessageHolder inboundHolder() {
        return this._holder;
    }
    
    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return this._op.setOutbound(this, message, null);
    }
    
    @Override
    public Subscription outbound(Observable<? extends Object> message, final WritePolicy writePolicy) {
        return this._op.setOutbound(this, message, writePolicy);
    }

    @Override
    public void close() {
        fireClosed(new CloseException());
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
    public Object transport() {
        return this._channel;
    }
    
    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate(this);
    }

    @Override
    public Action1<Action1<HttpTrade>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }

    @Override
    public Action0 doOnTerminate(Action1<HttpTrade> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    boolean isKeepAlive() {
        return this._isKeepAlive;
    }
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    
    DefaultHttpTrade(final Channel channel) {
        
        if (!channel.eventLoop().inEventLoop()) {
            throw new RuntimeException("Can't create trade out of channel(" + channel +")'s eventLoop.");
        }
        this._channel = channel;
        this._terminateAwareSupport = 
            new TerminateAwareSupport<HttpTrade>(this._selector);
        
        this._holder = new HttpMessageHolder();
        doOnTerminate(this._holder.closer());
        
        final Observable<? extends HttpObject> inbound = 
            Observable.unsafeCreate(new Observable.OnSubscribe<HttpObject>() {
                @Override
                public void call(final Subscriber<? super HttpObject> subscriber) {
                    initInboundHandler(subscriber);
                }})
            .compose(this._holder.<HttpObject>assembleAndHold())
            .cache()
            .compose(RxNettys.duplicateHttpContent());
        
        inbound.subscribe(
            RxSubscribers.ignoreNext(),
            new Action1<Throwable>() {
                @Override
                public void call(final Throwable e) {
                    LOG.warn("HttpTrade: {}'s inbound with onError {}", 
                        this, ExceptionUtils.exception2detail(e));
                }});
        
        this._cachedInbound = 
            Observable.unsafeCreate(new OnSubscribe<HttpObject>() {
                @Override
                public void call(final Subscriber<? super HttpObject> subscriber) {
                    subscribeInbound(subscriber, inbound);
                }});
        
        Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.ON_EXCEPTION_CAUGHT,
                new Action1<Throwable>() {
                    @Override
                    public void call(final Throwable cause) {
                        fireClosed(cause);
                    }});
        
        Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        onChannelInactive();
                    }});
        
        Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.ON_CHANNEL_READCOMPLETE,
                new Action0() {
                    @Override
                    public void call() {
                        onReadComplete();
                    }});

        Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.ON_CHANNEL_WRITABILITYCHANGED,
                new Action0() {
                    @Override
                    public void call() {
                        onWritabilityChanged();
                    }});
        
        this._traffic = Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.TRAFFICCOUNTER);
        
        this._op = this._selector.build(Op.class, OP_ACTIVE, OP_UNACTIVE);
        
        if (!channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        }
    }

    private void subscribeInbound(final Subscriber<? super HttpObject> subscriber,
            final Observable<? extends HttpObject> inbound) {
        if (!subscriber.isUnsubscribed()) {
            final Subscriber<? super HttpObject> serializedSubscriber = RxSubscribers.serialized(subscriber);
            this._subscribers.add(serializedSubscriber);
            serializedSubscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _subscribers.remove(serializedSubscriber);
                }}));
            inbound.subscribe(serializedSubscriber);
        }
    }
    
    private void initInboundHandler(final Subscriber<? super HttpObject> subscriber) {
        final ChannelHandler handler = new SimpleChannelInboundHandler<HttpObject>(false) {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx,
                    final HttpObject inmsg) throws Exception {
                _op.inboundOnNext(DefaultHttpTrade.this, subscriber, inmsg);
            }};
        Nettys.applyHandler(this._channel.pipeline(), HttpHandlers.ON_MESSAGE, handler);
        // TBD, check _inboundHandler's status, at most only once
        this._inboundHandler = handler;
    }

    private void inboundOnNext(
            final Subscriber<? super HttpObject> subscriber,
            final HttpObject inmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("HttpTrade: channel({}) invoke channelRead0 and call with msg({}).",
                this._channel, inmsg);
        }
        markStartRecving();
        if (inmsg instanceof HttpRequest) {
            onHttpRequest((HttpRequest)inmsg);
        }
        
        try {
            subscriber.onNext(inmsg);
        } finally {
            //  SimpleChannelInboundHandler 设置为 !NOT! autorelease
            //  因此可在 onNext 之后尽早的 手动 release request's message
            ReferenceCountUtil.release(inmsg);
            if (inmsg instanceof LastHttpContent) {
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
                markEndofRecving();
                removeInboundHandler();
                subscriber.onCompleted();
            }
        }
    }
    
    private void onHttpRequest(final HttpRequest req) {
        this._requestMethod = req.method().name();
        this._requestUri = req.uri();
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
    }
    
    private Subscription setOutbound(
            final Observable<? extends Object> outbound, 
            final WritePolicy writePolicy) {
        if (this._isOutboundSetted.compareAndSet(false, true)) {
            if (null!=writePolicy) {
                writePolicy.applyTo(buildOutboundable());
            }
            final Subscription subscription = outbound.subscribe(buildOutboundObserver());
            outboundSubscriptionUpdater.set(this, subscription);
            return subscription;
        } else {
            LOG.warn("trade({}) 's outbound message has setted, ignore this outbound({})",
                    this, outbound);
            return null;
        }
    }
    
    private Observer<Object> buildOutboundObserver() {
        return new Observer<Object>() {
                @Override
                public void onCompleted() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("response invoke onCompleted for trade: {}", DefaultHttpTrade.this);
                    }
                    _op.outboundOnCompleted(DefaultHttpTrade.this);
                }

                @Override
                public void onError(final Throwable e) {
                    LOG.warn("response invoke onError with ({}), try close trade: {}",
                            ExceptionUtils.exception2detail(e), DefaultHttpTrade.this);
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object outmsg) {
                    _op.outboundOnNext(DefaultHttpTrade.this, outmsg);
                }};
    }
    
    private Outboundable buildOutboundable() {
        return new Outboundable() {
            @Override
            public void setFlushPerWrite(final boolean isFlushPerWrite) {
                _isFlushPerWrite = isFlushPerWrite;
            }

            @Override
            public void setWriteBufferWaterMark(final int low, final int high) {
                _op.setWriteBufferWaterMark(DefaultHttpTrade.this, low, high);
            }

            @Override
            public Observable<Boolean> writability() {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(final Subscriber<? super Boolean> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            _op.runAtEventLoop(DefaultHttpTrade.this, new Runnable() {
                                @Override
                                public void run() {
                                    addWritabilitySubscriber(subscriber);
                                }});
                        }
                    }});
            }

            @Override
            public Observable<Object> sended() {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Object>() {
                    @Override
                    public void call(final Subscriber<? super Object> subscriber) {
                        addSendedSubscriber(subscriber);
                    }});
            }};
    }

    private void addWritabilitySubscriber(final Subscriber<? super Boolean> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            subscriber.onNext(this._op.isWritable(this));
            this._writabilityObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _writabilityObserver.removeComponent(subscriber);
                }}));
        }
    }
    
    private void addSendedSubscriber(final Subscriber<? super Object> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._sendedObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _sendedObserver.removeComponent(subscriber);
                }}));
        }
    }
    
    static class CloseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CloseException() {
            super("close()");
        }
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultHttpTrade)args[0]).doClosed((Throwable)args[1]);
        }};
        
    private void doClosed(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing active trade[channel: {}] "
                    + "with "
                    + "/transactionStatus({})"
                    + "/isKeepAlive({}),"
                    + "cause by {}", 
                    this._channel, 
                    this.transactionStatusAsString(),
                    this.isKeepAlive(),
                    errorAsString(e));
        }
        fireAllSubscriberUnactive(e);
        
        removeInboundHandler();
        unsubscribeOutbound();
        
        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private static String errorAsString(final Throwable e) {
        return e != null 
            ?
                (e instanceof CloseException)
                ? "close()" 
                : ExceptionUtils.exception2detail(e)
            : "no error"
            ;
    }

    private String transactionStatusAsString() {
        switch(transactionStatus()) {
        case STATUS_IDLE:
            return "IDLE";
        case STATUS_SEND:
            return "SEND";
        case STATUS_RECV:
            return "RECV";
        case STATUS_RECV_END:
            return "RECV_END";
        default:
            return "UNKNOWN";
        }
    }
    
    private void fireAllSubscriberUnactive(final Throwable reason) {
        this._unactiveReason = null != reason 
                ? reason 
                : new RuntimeException("inbound unactived");
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject>[] subscribers = 
            (Subscriber<? super HttpObject>[])this._subscribers.toArray(new Subscriber[0]);
        for (Subscriber<? super HttpObject> subscriber : subscribers) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onError(this._unactiveReason);
                } catch (Exception e) {
                    LOG.warn("exception when invoke ({}).onError, detail: {}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }
    
    private void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }
    
    private void onChannelInactive() {
        if (inTransacting()) {
            fireClosed(new TransportException("channelInactive of " + this._channel));
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("channel inactive after transaction finished, MAYBE Connection: close");
            }
            // close normally
            close();
        }
    }

    private void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (inRecving()) {
            final Single<?> when = this._whenToRead;
            if (null != when) {
                when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        _op.readMessage(DefaultHttpTrade.this);
                    }});
            } else {
                //  perform read at once
                _op.readMessage(DefaultHttpTrade.this);
            }
        }
    }

    private static final Action1_N<Subscriber<? super Boolean>> ON_WRITABILITY_CHGED = new Action1_N<Subscriber<? super Boolean>>() {
        @Override
        public void call(final Subscriber<? super Boolean> subscriber, final Object... args) {
            final Boolean isWritable = (Boolean)args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onNext(isWritable);
                } catch (Exception e) {
                    LOG.warn("exception when invoke onNext({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        }};
        
    private void onWritabilityChanged() {
        this._writabilityObserver.foreachComponent(ON_WRITABILITY_CHGED, this._op.isWritable(this));
    }
    
    private static final Action1_N<Subscriber<? super Object>> ON_SENDED = new Action1_N<Subscriber<? super Object>>() {
        @Override
        public void call(final Subscriber<? super Object> subscriber, final Object... args) {
            final Object outmsg = args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onNext(outmsg);
                } catch (Exception e) {
                    LOG.warn("exception when invoke onNext({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        }};

    private void onOutboundMsgSended(final Object outmsg) {
        this._sendedObserver.foreachComponent(ON_SENDED, outmsg);
    }
    
    private ChannelFuture sendOutbound(final Object outmsg) {
        return this._isFlushPerWrite
                ? this._channel.writeAndFlush(ReferenceCountUtil.retain(outmsg))
                : this._channel.write(ReferenceCountUtil.retain(outmsg));
    }
    
    private void outboundOnNext(final Object outmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http response msg {}", outmsg);
        }
        // set in transacting flag
        markStartSending();
        
        sendOutbound(outmsg)
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("send http response msg {} success.", outmsg);
                    }
                    onOutboundMsgSended(outmsg);
                } else {
                    LOG.warn("exception when send http resp: {}, detail: {}",
                            outmsg, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send response error", future.cause()));
                }
            }});
    }
    
    private void outboundOnCompleted() {
        // force flush for _isFlushPerWrite = false
        //  reference: https://github.com/netty/netty/commit/789e323b79d642ea2c0a024cb1c839654b7b8fad
        //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
        //  Detail:
        //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
        //
        //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        //
        // See https://github.com/netty/netty/issues/2983 for more information.
        this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                endTransaction();
                if (future.isSuccess()) {
                    // close normally
                    close();
                } else {
                    fireClosed(new TransportException("flush response error", future.cause()));
                }
            }});
    }
    
    private void readMessage() {
        if (inRecving()) {
            this._channel.read();
            this._unreadBegin = 0;
            readBeginUpdater.compareAndSet(this, 0, System.currentTimeMillis());
        }
    }
    
    private void unsubscribeOutbound() {
        final Subscription subscription = outboundSubscriptionUpdater.getAndSet(this, null);
        if (null != subscription
            && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
    }
    
    private void removeInboundHandler() {
        final ChannelHandler handler = inboundHandlerUpdater.getAndSet(this, null);
        if (null != handler) {
            Nettys.actionToRemoveHandler(this._channel, handler).call();
        }
    }
    
    private void markStartRecving() {
        transactionUpdater.compareAndSet(this, STATUS_IDLE, STATUS_RECV);
    }
    
    private void markEndofRecving() {
        transactionUpdater.compareAndSet(this, STATUS_RECV, STATUS_RECV_END);
    }
    
    private void markStartSending() {
        transactionUpdater.compareAndSet(this, STATUS_RECV_END, STATUS_SEND);
    }
    
    private void endTransaction() {
        transactionUpdater.compareAndSet(this, STATUS_SEND, STATUS_IDLE);
    }
    
    private int transactionStatus() {
        return transactionUpdater.get(this);
    }
    
    private boolean inRecving() {
        return transactionStatus() == STATUS_RECV;
    }
    
    boolean inTransacting() {
        return transactionStatus() > STATUS_IDLE;
    }
    
    private static final AtomicIntegerFieldUpdater<DefaultHttpTrade> transactionUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpTrade.class, "_transactionStatus");
    
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_RECV = 1;
    private static final int STATUS_RECV_END = 2;
    private static final int STATUS_SEND = 3;
    
    @SuppressWarnings("unused")
    private volatile int _transactionStatus = STATUS_IDLE;
    
    private static final AtomicLongFieldUpdater<DefaultHttpTrade> readBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultHttpTrade.class, "_readBegin");
    
    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;
    
    private volatile long _unreadBegin = 0;
    private volatile Single<?> _whenToRead = null;
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpTrade, ChannelHandler> inboundHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpTrade.class, ChannelHandler.class, "_inboundHandler");
    
    @SuppressWarnings("unused")
    private volatile ChannelHandler _inboundHandler = null;
    
    private final HttpMessageHolder _holder;
    private final List<Subscriber<? super HttpObject>> _subscribers = 
            new CopyOnWriteArrayList<>();
    private volatile Throwable _unactiveReason = null;
    private final Observable<HttpObject> _cachedInbound;
    
    private volatile boolean _isFlushPerWrite = false;
    
    private static final AtomicReferenceFieldUpdater<DefaultHttpTrade, Subscription> outboundSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultHttpTrade.class, Subscription.class, "_outboundSubscription");
    
    @SuppressWarnings("unused")
    private volatile Subscription _outboundSubscription;
    
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    
    private final TerminateAwareSupport<HttpTrade> _terminateAwareSupport;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _traffic;
    private String _requestMethod;
    private String _requestUri;
    
    private volatile boolean _isKeepAlive = false;
    
    private final COWCompositeSupport<Subscriber<? super Boolean>> _writabilityObserver = 
            new COWCompositeSupport<>();
    
    private final COWCompositeSupport<Subscriber<? super Object>> _sendedObserver = 
            new COWCompositeSupport<>();
    
    private final Op _op;
    
    protected interface Op {
//        public <T extends ChannelHandler> T enable(
//                final DefaultHttpTrade trade, 
//                final HttpHandlers handlerType, final Object... args);

        public void inboundOnNext(
                final DefaultHttpTrade trade,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject msg);
        
        public Subscription setOutbound(final DefaultHttpTrade trade,
                final Observable<? extends Object> outbound, 
                final WritePolicy writePolicy);
        
        public void outboundOnNext(
                final DefaultHttpTrade trade,
                final Object msg);
        
        public void outboundOnCompleted(
                final DefaultHttpTrade trade);
        
        public void readMessage(final DefaultHttpTrade trade);

        public void setWriteBufferWaterMark(final DefaultHttpTrade trade,
                final int low, final int high);
        
        public boolean isWritable(final DefaultHttpTrade trade);
        
        public Future<?> runAtEventLoop(final DefaultHttpTrade trade, final Runnable task);
    }
    
    private static final Op OP_ACTIVE = new Op() {
//        public <T extends ChannelHandler> T enable(
//                final DefaultHttpTrade trade, 
//                final HttpHandlers handlerType, final Object... args) {
//            return Nettys.applyToChannel(trade.onTerminate(), 
//                    trade._channel, handlerType, args);
//        }
        
        public void inboundOnNext(
                final DefaultHttpTrade trade,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject inmsg) {
            trade.inboundOnNext(subscriber, inmsg);
        }
        
        @Override
        public Subscription setOutbound(
                final DefaultHttpTrade trade,
                final Observable<? extends Object> outbound,
                final WritePolicy writePolicy) {
            return trade.setOutbound(outbound, writePolicy);
        }
        
        @Override
        public void outboundOnNext(
                final DefaultHttpTrade trade,
                final Object outmsg) {
            trade.outboundOnNext(outmsg);
        }
        
        @Override
        public void outboundOnCompleted(
                final DefaultHttpTrade trade) {
            trade.outboundOnCompleted();
        }
        
        @Override
        public void readMessage(final DefaultHttpTrade trade) {
            trade.readMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final DefaultHttpTrade trade,
                final int low, final int high) {
            trade._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            if (LOG.isInfoEnabled()) {
                LOG.info("channel({}) setWriteBufferWaterMark with low:{} high:{}", 
                    trade._channel, low, high);
            }
        }
        
        @Override
        public boolean isWritable(final DefaultHttpTrade trade) {
            return trade._channel.isWritable();
        }
        
        @Override
        public Future<?> runAtEventLoop(final DefaultHttpTrade trade, final Runnable task) {
            return trade._channel.eventLoop().submit(task);
        }
    };
    
    private static final Op OP_UNACTIVE = new Op() {
//        public <T extends ChannelHandler> T enable(
//                final DefaultHttpTrade trade, 
//                final HttpHandlers handlerType, final Object... args) {
//            return null;
//        }
        public void inboundOnNext(
                final DefaultHttpTrade trade,
                final Subscriber<? super HttpObject> subscriber,
                final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            if (LOG.isDebugEnabled()) {
                LOG.debug("HttpTrade(inactive): channelRead0 and release msg({}).", inmsg);
            }
        }
        
        @Override
        public Subscription setOutbound(final DefaultHttpTrade trade,
                final Observable<? extends Object> outbound,
                final WritePolicy writePolicy
                ) {
            return null;
        }
        
        @Override
        public void outboundOnNext(
                final DefaultHttpTrade trade,
                final Object outmsg) {
        }
        
        @Override
        public void outboundOnCompleted(final DefaultHttpTrade trade) {
        }
        
        @Override
        public void readMessage(final DefaultHttpTrade trade) {
        }

        @Override
        public void setWriteBufferWaterMark(final DefaultHttpTrade trade,
                final int low, final int high) {
        }
        
        @Override
        public boolean isWritable(final DefaultHttpTrade trade) {
            return false;
        }
        
        @Override
        public Future<?> runAtEventLoop(final DefaultHttpTrade trade, final Runnable task) {
            return null;
        }
    };
}
