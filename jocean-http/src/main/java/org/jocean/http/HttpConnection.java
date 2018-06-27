package org.jocean.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.ReadPolicy.Intraffic;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAware;
import org.jocean.idiom.TerminateAwareSupport;
import org.jocean.idiom.rx.Action1_N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
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

public abstract class HttpConnection<T> implements Inbound, Outbound, AutoCloseable, TerminateAware<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpConnection.class);

    protected final InterfaceSelector _selector = new InterfaceSelector();

    protected HttpConnection(final Channel channel) {

        this._terminateAwareSupport =
                new TerminateAwareSupport<T>(this._selector);

        this._channel = channel;
        this._iobaseop = this._selector.build(IOBaseOp.class, IOOP_ACTIVE, IOOP_UNACTIVE);
        this._traffic = Nettys.applyToChannel(onTerminate(),
                this._channel,
                HttpHandlers.TRAFFICCOUNTER);

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

        if (!this._channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        }
    }

    private final IOBaseOp _iobaseop;

    protected final Channel _channel;

    private volatile boolean _isFlushPerWrite = false;

    private final TrafficCounter _traffic;

    protected final TerminateAwareSupport<T> _terminateAwareSupport;

    @Override
    public void close() {
        fireClosed(new CloseException());
    }

//    @Override
    public Action0 closer() {
        return new Action0() {
            @Override
            public void call() {
                close();
            }};
    }

//    @Override
    public Object transport() {
        return this._channel;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate((T) this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action1<Action1<T>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf((T) this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action0 doOnTerminate(final Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate((T) this, onTerminate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action0 doOnTerminate(final Action1<T> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate((T) this, onTerminate);
    }

    @Override
    public WriteCtrl writeCtrl() {
        return buildWriteCtrl();
    }

    private WriteCtrl buildWriteCtrl() {
        return new WriteCtrl() {
            @Override
            public void setFlushPerWrite(final boolean isFlushPerWrite) {
                _isFlushPerWrite = isFlushPerWrite;
            }

            @Override
            public void setWriteBufferWaterMark(final int low, final int high) {
                _iobaseop.setWriteBufferWaterMark(HttpConnection.this, low, high);
            }

            @Override
            public Observable<Boolean> writability() {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(final Subscriber<? super Boolean> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            _iobaseop.runAtEventLoop(HttpConnection.this, new Runnable() {
                                @Override
                                public void run() {
                                    addWritabilitySubscriber(subscriber);
                                }});
                        }
                    }});
            }

            @Override
            public Observable<Object> sending() {
                return Observable.unsafeCreate(new OnSubscribe<Object>() {
                    @Override
                    public void call(final Subscriber<? super Object> subscriber) {
                        addSendingSubscriber(subscriber);
                    }});
            }

            @Override
            public Observable<Object> sended() {
                return Observable.unsafeCreate(new OnSubscribe<Object>() {
                    @Override
                    public void call(final Subscriber<? super Object> subscriber) {
                        addSendedSubscriber(subscriber);
                    }});
            }};
    }

    private void addWritabilitySubscriber(final Subscriber<? super Boolean> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            subscriber.onNext(this._iobaseop.isWritable(this));
            this._writabilityObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _writabilityObserver.removeComponent(subscriber);
                }}));
        }
    }

    private void addSendingSubscriber(final Subscriber<? super Object> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._sendingObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _sendingObserver.removeComponent(subscriber);
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

    private final COWCompositeSupport<Subscriber<? super Boolean>> _writabilityObserver =
            new COWCompositeSupport<>();

    private final COWCompositeSupport<Subscriber<? super Object>> _sendingObserver =
            new COWCompositeSupport<>();

    private final COWCompositeSupport<Subscriber<? super Object>> _sendedObserver =
            new COWCompositeSupport<>();

    private static final Action1_N<Subscriber<? super Boolean>> ON_WRITABILITY_CHGED = new Action1_N<Subscriber<? super Boolean>>() {
        @Override
        public void call(final Subscriber<? super Boolean> subscriber, final Object... args) {
            final Boolean isWritable = (Boolean)args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onNext(isWritable);
                } catch (final Exception e) {
                    LOG.warn("exception when invoke onNext({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        }};

    private void onWritabilityChanged() {
        this._writabilityObserver.foreachComponent(ON_WRITABILITY_CHGED, this._iobaseop.isWritable(this));
    }

    private static final Action1_N<Subscriber<? super Object>> OBJ_ON_NEXT = new Action1_N<Subscriber<? super Object>>() {
        @Override
        public void call(final Subscriber<? super Object> subscriber, final Object... args) {
            final Object obj = args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onNext(obj);
                } catch (final Exception e) {
                    LOG.warn("exception when invoke onNext({}), detail: {}", subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }};

    @Override
    public void setReadPolicy(final ReadPolicy readPolicy) {
        this._iobaseop.runAtEventLoop(this, new Runnable() {
            @Override
            public void run() {
                setReadPolicy0(readPolicy);
            }});
    }

    private void setReadPolicy0(final ReadPolicy readPolicy) {
        this._whenToRead = null != readPolicy
                ? readPolicy.whenToRead(buildIntraffic())
                : null;
        final Subscription pendingRead = pendingReadUpdater.getAndSet(this, null);
        if (null != pendingRead && !pendingRead.isUnsubscribed()) {
            pendingRead.unsubscribe();
            // perform other read action
            onReadComplete();
        }
    }

    static abstract class ReadAction implements HttpObject, DoRead {
        @Override
        public DecoderResult decoderResult() {
            return null;
        }

        @Override
        public void setDecoderResult(final DecoderResult result) {
        }

        @Override
        public DecoderResult getDecoderResult() {
            return null;
        }
    }

    private void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (needRead()) {
            @SuppressWarnings("unchecked")
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber = inboundSubscriberUpdater.get(this);
            if (null != subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onNext(DisposableWrapperUtil.wrap(new ReadAction() {
                        @Override
                        public void read() {
                            LOG.debug("invoke read for {}", HttpConnection.this);
                            _iobaseop.readMessage(HttpConnection.this);
                        }}, (Action1<HttpObject>)null));
                }
            }

            /*
            final Single<?> when = this._whenToRead;
            if (null != when) {
                final Subscription pendingRead = when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        _iobaseop.readMessage(HttpConnection.this);
                    }});

                pendingReadUpdater.set(this, pendingRead);
            } else {
                //  perform read at once
                _iobaseop.readMessage(HttpConnection.this);
            }
            */
        }
    }

//    @Override
    public TrafficCounter traffic() {
        return this._traffic;
    }

//    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    private Intraffic buildIntraffic() {
        return new Intraffic() {
            @Override
            public long durationFromRead() {
                final long begin = _unreadBegin;
                return 0 == begin ? 0 : System.currentTimeMillis() - begin;
            }

            @Override
            public long durationFromBegin() {
                return Math.max(System.currentTimeMillis() - readBeginUpdater.get(HttpConnection.this), 1L);
            }

            @Override
            public long inboundBytes() {
                return traffic().inboundBytes();
            }};
    }

    protected void readMessage() {
        if (needRead()) {
            LOG.info("read message for channel {}", this._channel);
            this._channel.read();
            this._unreadBegin = 0;
            readBeginUpdater.compareAndSet(this, 0, System.currentTimeMillis());
        }
    }

    protected boolean holdInboundAndInstallHandler(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        if (holdInboundSubscriber(subscriber)) {
            final ChannelHandler handler = buildInboundHandler(subscriber);
            Nettys.applyHandler(this._channel.pipeline(), HttpHandlers.ON_MESSAGE, handler);
            setInboundHandler(handler);
            return true;
        } else {
            return false;
        }
    }

    protected boolean unholdInboundAndUninstallHandler(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        if (unholdInboundSubscriber(subscriber)) {
            removeInboundHandler();
            return true;
        } else {
            return false;
        }
    }

    private boolean holdInboundSubscriber(final Subscriber<?> subscriber) {
        return inboundSubscriberUpdater.compareAndSet(this, null, subscriber);
    }

    private boolean unholdInboundSubscriber(final Subscriber<?> subscriber) {
        return inboundSubscriberUpdater.compareAndSet(this, subscriber, null);
    }

    private void releaseInboundWithError(final Throwable error) {
        final Subscriber<?> inboundSubscriber = inboundSubscriberUpdater.getAndSet(this, null);
        if (null != inboundSubscriber && !inboundSubscriber.isUnsubscribed()) {
            try {
                inboundSubscriber.onError(error);
            } catch (final Exception e) {
                LOG.warn("exception when invoke {}.onError, detail: {}",
                    inboundSubscriber, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private SimpleChannelInboundHandler<HttpObject> buildInboundHandler(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        return new SimpleChannelInboundHandler<HttpObject>(false) {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject inmsg) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("HttpConnection: channel({})/handler({}): channelRead0 and call with msg({}).",
                        ctx.channel(), ctx.name(), inmsg);
                }
                _iobaseop.onInmsgRecvd(HttpConnection.this, subscriber, inmsg);
            }};
    }

    private void processInmsg(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber, final HttpObject inmsg) {

        onInboundMessage(inmsg);

        try {
            subscriber.onNext(DisposableWrapperUtil.disposeOn(this, RxNettys.wrap4release(inmsg)));
        } finally {
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
                if (unholdInboundAndUninstallHandler(subscriber)) {
                    onInboundCompleted();
                    subscriber.onCompleted();
                }
            }
        }
    }

    private void doSendOutmsg(final Object outmsg) {
        if (outmsg instanceof DoFlush) {
            this._channel.flush();
        } else {
            onOutmsgSending(outmsg);
            beforeSendingOutbound(outmsg);

            writeOutmsgToChannel(outmsg)
            .addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future)
                        throws Exception {
                    if (future.isSuccess()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("send http outmsg {} success.", outmsg);
                        }
                        onOutmsgSended(outmsg);
                    } else {
                        LOG.warn("exception when send outmsg: {}, detail: {}",
                                outmsg, ExceptionUtils.exception2detail(future.cause()));
                        fireClosed(new TransportException("send outmsg error", future.cause()));
                    }
                }});
        }
    }

    private ChannelFuture writeOutmsgToChannel(Object outmsg) {
        while (outmsg instanceof DisposableWrapper) {
            outmsg = ((DisposableWrapper<?>)outmsg).unwrap();
        }
        return this._isFlushPerWrite
                ? this._channel.writeAndFlush(ReferenceCountUtil.retain(outmsg))
                : this._channel.write(ReferenceCountUtil.retain(outmsg));
    }

    private void onOutmsgSending(final Object outmsg) {
        this._sendingObserver.foreachComponent(OBJ_ON_NEXT, outmsg);
    }

    private void onOutmsgSended(final Object outmsg) {
        this._sendedObserver.foreachComponent(OBJ_ON_NEXT, outmsg);
    }

    protected void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((HttpConnection<?>)args[0]).doClosed((Throwable)args[1]);
        }
    };

    @SuppressWarnings("unchecked")
    private void doClosed(final Throwable e) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing iobase: {}\r\n"
                    + "cause by {}",
                    toString(),
                    errorAsString(e));
        }

        removeInboundHandler();

        // notify request or response Subscriber with error
        releaseInboundWithError(e);

        unsubscribeOutbound();

        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates((T) this);
    }

    protected static String errorAsString(final Throwable e) {
        return e != null
            ?
                (e instanceof CloseException)
                ? "close()"
                : ExceptionUtils.exception2detail(e)
            : "no error"
            ;
    }

    private void setInboundHandler(final ChannelHandler handler) {
        inboundHandlerUpdater.set(this, handler);
    }

    private void removeInboundHandler() {
        final ChannelHandler handler = inboundHandlerUpdater.getAndSet(this, null);
        if (null != handler) {
            Nettys.actionToRemoveHandler(this._channel, handler).call();
        }
    }

    private Subscription doSetOutbound(final Observable<? extends Object> outbound) {
        if (this._isOutboundSetted.compareAndSet(false, true)) {
            final Subscription subscription = outbound.subscribe(buildOutboundObserver());
            setOutboundSubscription(subscription);
            return subscription;
        } else {
            LOG.warn("iobase ({}) 's outbound message has setted, ignore this outbound({})",
                    this, outbound);
            return null;
        }
    }

    private Observer<Object> buildOutboundObserver() {
        return new Observer<Object>() {
                @Override
                public void onCompleted() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("outound invoke onCompleted for iobase: {}", HttpConnection.this);
                    }
                    _iobaseop.onOutmsgCompleted(HttpConnection.this);
                }

                @Override
                public void onError(final Throwable e) {
                    if (!(e instanceof CloseException)) {
                        LOG.warn("outound invoke onError with ({}), try close iobase: {}",
                                ExceptionUtils.exception2detail(e), HttpConnection.this);
                    }
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object outmsg) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("outbound invoke onNext({}) for iobase: {}", outmsg, HttpConnection.this);
                    }
                    _iobaseop.sendOutmsg(HttpConnection.this, outmsg);
                }};
    }

    private void setOutboundSubscription(final Subscription subscription) {
        outboundSubscriptionUpdater.set(this, subscription);
    }

    private void unsubscribeOutbound() {
        final Subscription subscription = outboundSubscriptionUpdater.getAndSet(this, null);
        if (null != subscription && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
    }

    protected Subscription setOutbound(final Observable<? extends Object> message) {
        return this._iobaseop.setOutbound(this, message);
    }

    protected abstract boolean needRead();

    protected abstract void onInboundMessage(final HttpObject inmsg);

    protected abstract void onInboundCompleted();

    protected abstract void beforeSendingOutbound(final Object outmsg);

    protected abstract void onOutboundCompleted();

    protected abstract void onChannelInactive();

    @SuppressWarnings("unused")
    private volatile Single<?> _whenToRead = null;

    private volatile long _unreadBegin = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<HttpConnection> readBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(HttpConnection.class, "_readBegin");

    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpConnection, Subscription> pendingReadUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpConnection.class, Subscription.class, "_pendingRead");

    @SuppressWarnings("unused")
    private volatile Subscription _pendingRead = null;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpConnection, Subscriber> inboundSubscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpConnection.class, Subscriber.class, "_inboundSubscriber");

    @SuppressWarnings("unused")
    private volatile Subscriber<?> _inboundSubscriber;

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpConnection, ChannelHandler> inboundHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpConnection.class, ChannelHandler.class, "_inboundHandler");

    @SuppressWarnings("unused")
    private volatile ChannelHandler _inboundHandler;

    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpConnection, Subscription> outboundSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpConnection.class, Subscription.class, "_outboundSubscription");

    @SuppressWarnings("unused")
    private volatile Subscription _outboundSubscription;

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpConnection> transactionUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpConnection.class, "_transactionStatus");

    @SuppressWarnings("unused")
    private volatile int _transactionStatus = STATUS_IDLE;

    protected static final int STATUS_IDLE = 0;

    public boolean inTransacting() {
        return transactionStatus() > STATUS_IDLE;
    }

    protected int transactionStatus() {
        return transactionUpdater.get(this);
    }

    protected void transferStatus(final int oldStatus, final int newStatus) {
        transactionUpdater.compareAndSet(this, oldStatus, newStatus);
    }

    protected interface IOBaseOp {
        public void onInmsgRecvd(
                final HttpConnection<?> io,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject msg);

        public Subscription setOutbound(final HttpConnection<?> io, final Observable<? extends Object> outbound);

        public void sendOutmsg(final HttpConnection<?> io, final Object msg);

        public void onOutmsgCompleted(final HttpConnection<?> io);

        public void readMessage(final HttpConnection<?> io);

        public void setWriteBufferWaterMark(final HttpConnection<?> io, final int low, final int high);

        public boolean isWritable(final HttpConnection<?> io);

        public Future<?> runAtEventLoop(final HttpConnection<?> io, final Runnable task);
    }

    private static final IOBaseOp IOOP_ACTIVE = new IOBaseOp() {
        @Override
        public void onInmsgRecvd(
                final HttpConnection<?> iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject inmsg) {
            iobase.processInmsg(subscriber, inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpConnection<?> iobase, final Observable<? extends Object> outbound) {
            return iobase.doSetOutbound(outbound);
        }

        @Override
        public void sendOutmsg(final HttpConnection<?> iobase, final Object msg) {
            iobase.doSendOutmsg(msg);
        }

        @Override
        public void onOutmsgCompleted(final HttpConnection<?> iobase) {
            iobase.onOutboundCompleted();
        }

        @Override
        public void readMessage(final HttpConnection<?> iobase) {
            iobase.readMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final HttpConnection<?> iobase, final int low, final int high) {
            iobase._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            if (LOG.isInfoEnabled()) {
                LOG.info("channel({}) setWriteBufferWaterMark with low:{} high:{}", iobase._channel, low, high);
            }
        }

        @Override
        public boolean isWritable(final HttpConnection<?> iobase) {
            return iobase._channel.isWritable();
        }

        @Override
        public Future<?> runAtEventLoop(final HttpConnection<?> iobase, final Runnable task) {
            return iobase._channel.eventLoop().submit(task);
        }
    };

    private static final IOBaseOp IOOP_UNACTIVE = new IOBaseOp() {
        @Override
        public void onInmsgRecvd(
                final HttpConnection<?> iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            LOG.warn("HttpConnection(inactive): channelRead0 and release msg({}).", inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpConnection<?> iobase, final Observable<? extends Object> outbound) {
            return null;
        }

        @Override
        public void sendOutmsg(final HttpConnection<?> iobase, final Object outmsg) {}

        @Override
        public void onOutmsgCompleted(final HttpConnection<?> iobase) {}

        @Override
        public void readMessage(final HttpConnection<?> iobase) {}

        @Override
        public void setWriteBufferWaterMark(final HttpConnection<?> iobase, final int low, final int high) {}

        @Override
        public boolean isWritable(final HttpConnection<?> iobase) {
            return false;
        }

        @Override
        public Future<?> runAtEventLoop(final HttpConnection<?> iobase, final Runnable task) {
            return null;
        }
    };
}
