package org.jocean.http;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.Stepable;
import org.jocean.idiom.TerminateAware;
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
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

public abstract class HttpConnection<T> implements Inbound, Outbound, AutoCloseable, TerminateAware<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(HttpConnection.class);

    protected final InterfaceSelector _selector = new InterfaceSelector();

    protected HttpConnection(final Channel channel) {

        this._terminateAwareSupport =
                new TerminateAwareSupport<T>(this._selector);

        this._channel = channel;
        this._op = this._selector.build(ConnectionOp.class, WHEN_ACTIVE, WHEN_UNACTIVE);
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

    private final ConnectionOp _op;

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
    public Intraffic intraffic() {
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
                _op.setWriteBufferWaterMark(HttpConnection.this, low, high);
            }

            @Override
            public Observable<Boolean> writability() {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(final Subscriber<? super Boolean> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            _op.runAtEventLoop(HttpConnection.this, new Runnable() {
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
            subscriber.onNext(this._op.isWritable(this));
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
        this._writabilityObserver.foreachComponent(ON_WRITABILITY_CHGED, this._op.isWritable(this));
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

    private void onReadComplete() {
        //  可通过 _inmsgRecvd 的状态，判断是否已经解码出有效 inmsg
        if (!this._inmsgRecvd) {
            //  SSL enabled 连接:
            //  收到 READ COMPLETE 事件时，可能会由于当前接收到的数据不够，还未能解出有效的 inmsg ，
            //  此时应继续调用 channel.read(), 来继续获取对端发送来的 加密数据
            LOG.debug("!NO! inmsg received, continue read for {}", this);
            readMessage();
        } else {
            LOG.debug("inmsg received, stop read and wait for {}", this);
            this._unreadBegin = System.currentTimeMillis();
            final Subscriber<?> subscriber = inboundSubscriberUpdater.get(this);
            if (null != subscriber && !subscriber.isUnsubscribed()) {
                if (unholdInboundAndUninstallHandler(subscriber)) {
                    subscriber.onCompleted();
                }
            }
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

    protected void readMessage() {
        _op.readMessage(this);
    }

    private Observable<DisposableWrapper<? extends HttpObject>> rawInbound() {
        return Observable.unsafeCreate(new Observable.OnSubscribe<DisposableWrapper<? extends HttpObject>>() {
            @Override
            public void call(final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    if (!_op.attachInbound(HttpConnection.this, subscriber)) {
                        subscriber.onError(new RuntimeException("transaction in progress"));
                    }
                }
            }
        });
    }

    private Observable<HttpSlice> sliceWithNext(
            final Observable<DisposableWrapper<? extends HttpObject>> cachedInbound) {
        final CompositeSubscription cs = new CompositeSubscription();
        final Completable invokeNext = Completable.create(new Completable.OnSubscribe() {
                @Override
                public void call(final CompletableSubscriber subscriber) {
                    cs.add(Subscriptions.create(new Action0() {
                        @Override
                        public void call() {
                            subscriber.onCompleted();
                        }}));
                }});

        final Observable<HttpSlice> current = Observable.<HttpSlice>just(new HttpSlice() {
            @Override
            public void step() {
                cs.unsubscribe();
            }

            @Override
            public Observable<DisposableWrapper<? extends HttpObject>> element() {
                return cachedInbound;
            }});

        return current.concatWith(invokeNext.andThen(Observable.defer(new Func0<Observable<HttpSlice>>() {
            @Override
            public Observable<HttpSlice> call() {
                try {
                    return httpSlices();
                } finally {
                    readMessage();
                }
            }})));
    }

    private Observable<HttpSlice> lastSlice(final Observable<DisposableWrapper<? extends HttpObject>> cachedInbound) {
        return Observable.<HttpSlice>just(new HttpSlice() {
            @Override
            public void step() {}

            @Override
            public Observable<DisposableWrapper<? extends HttpObject>> element() {
                return cachedInbound;
            }});
    }

    private boolean hasNext(final DisposableWrapper<? extends HttpObject> last) {
        return !(last.unwrap() instanceof LastHttpContent);
    }

    private Action1<DisposableWrapper<? extends HttpObject>> checkInboundCompleted() {
        return new Action1<DisposableWrapper<? extends HttpObject>>() {
            @Override
            public void call(final DisposableWrapper<? extends HttpObject> dwh) {
                if (dwh.unwrap() instanceof LastHttpContent) {
                    /*
                     * netty 参考代码:
                     *   https://github.com/netty/netty/blob/netty-4.0.26.Final /codec/src/main/java/io/netty/handler/codec/ByteToMessageDecoder.java#L274
                     *   https://github.com/netty/netty/blob/netty-4.0.26.Final/codec-http/src/main/java/io/netty/handler/codec/http/HttpObjectDecoder.java#L398
                     * 从上述代码可知, 当Connection断开时，首先会检查是否满足特定条件 currentState ==
                     * State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() &&
                     * !chunked 即没有指定Content-Length头域，也不是CHUNKED传输模式
                     * ，此情况下，即会自动产生一个LastHttpContent .EMPTY_LAST_CONTENT实例
                     * 因此，无需在channelInactive处，针对该情况做特殊处理
                     */
                    onInboundCompleted();
                }
            }
        };
    }

    protected Observable<HttpSlice> httpSlices() {
        // install inbound slice subscriber
        final Observable<DisposableWrapper<? extends HttpObject>> cachedInbound = rawInbound()
                .doOnNext(checkInboundCompleted()).cache();
        // force subscribe for cache
        cachedInbound.subscribe(RxSubscribers.ignoreNext(), new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                LOG.warn("httpSlice's cached inbound meet onError {} for {}", errorAsString(e), HttpConnection.this);
            }
        });

        return cachedInbound.last().flatMap(new Func1<DisposableWrapper<? extends HttpObject>, Observable<HttpSlice>>() {
            @Override
            public Observable<HttpSlice> call(final DisposableWrapper<? extends HttpObject> last) {
                return hasNext(last) ? sliceWithNext(cachedInbound) : lastSlice(cachedInbound);
            }});
    }

    private void doReadMessage() {
        LOG.info("trigger read message for {}", this);
        this._channel.read();
        this._unreadBegin = 0;
        readBeginUpdater.compareAndSet(this, 0, System.currentTimeMillis());
    }

    private boolean holdInboundAndInstallHandler(
            final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber) {
        if (holdInboundSubscriber(subscriber)) {
            final ChannelHandler handler = buildInboundHandler(subscriber);
            Nettys.applyHandler(this._channel.pipeline(), HttpHandlers.ON_MESSAGE, handler);
            setInboundHandler(handler);
            this._inmsgRecvd = false;
            return true;
        } else {
            return false;
        }
    }

    private boolean unholdInboundAndUninstallHandler(final Subscriber<?> subscriber) {
        if (unholdInboundSubscriber(subscriber)) {
            removeInboundHandler();
            this._inmsgRecvd = false;
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

    private void invokeInboundOnError(final Throwable error) {
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

    private SimpleChannelInboundHandler<HttpObject> buildInboundHandler(final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber) {
        return new SimpleChannelInboundHandler<HttpObject>(false) {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject inmsg) throws Exception {
                LOG.debug("channelRead0 income msg({}) for {}", inmsg, HttpConnection.this);
                _op.onInmsgRecvd(HttpConnection.this, subscriber, inmsg);
            }};
    }

    private void processInmsg(final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber,
            final HttpObject inmsg) {

        onInboundMessage(inmsg);

        try {
            this._inmsgRecvd = true;
            subscriber.onNext(DisposableWrapperUtil.disposeOn(this, RxNettys.wrap4release(inmsg)));
        } finally {
        }
    }

    private Completable doFlush() {
        return RxNettys.future2Completable(this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER), false);
    }

    private void doSendOutmsg(final Object outmsg) {
        final ChannelFutureListener whenComplete = new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess()) {
                    LOG.debug("send outmsg({}) success for {}", outmsg, HttpConnection.this);
                    onOutmsgSended(outmsg);
                } else {
                    LOG.warn("exception when send outmsg({}) for {}, detail: {}",
                            outmsg, HttpConnection.this, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send outmsg error", future.cause()));
                }
            }};
        onOutmsgSending(outmsg);
        if (outmsg instanceof DoFlush) {
            this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(whenComplete);
        } else {
            writeOutmsgToChannel(outmsg).addListener(whenComplete);
        }
    }

    private ChannelFuture writeOutmsgToChannel(Object outmsg) {
        while (outmsg instanceof DisposableWrapper) {
            outmsg = ((DisposableWrapper<?>)outmsg).unwrap();
        }
        beforeSendingOutbound(outmsg);
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
        LOG.debug("closing {}, cause by {}", toString(), errorAsString(e));

        removeInboundHandler();

        // notify inbound Subscriber by error
        invokeInboundOnError(e);

        unsubscribeOutbound();

        //  fire all pending subscribers onError with unactived exception
        this._terminateAwareSupport.fireAllTerminates((T) this);
    }

    protected static String errorAsString(final Throwable e) {
        return e != null ? (e instanceof CloseException) ? "close()" : ExceptionUtils.exception2detail(e) : "no error";
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
        LOG.debug("doSetOutbound with outbound:{} for {}", outbound, this);
        final Subscription placeholder = buildPlaceholderSubscription();

        if (outboundSubscriptionUpdater.compareAndSet(this, null, placeholder)) {
            final Subscriber<Object> outboundSubscriber = buildOutboundSubscriber();
            final Subscription subscription = outbound.subscribe(outboundSubscriber);
            outboundSubscriptionUpdater.set(this, subscription);
            //  TODO:
            //  when outbound unsubscribe early, how to do (close HttpConnection instance ?)
            outboundSubscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    if (outboundSubscriptionUpdater.compareAndSet(HttpConnection.this, subscription, null)) {
                        LOG.debug("reset _outboundSubscription to null");
                    } else {
                        LOG.debug("_outboundSubscription has changed, MAYBE force replace to next outbound, ignore reset");
                    }
                }}));
            return subscription;
        } else {
            LOG.warn("outbound message has setted for {}, ignore this outbound({})", this, outbound);
            return null;
        }
    }

    private Subscriber<Object> buildOutboundSubscriber() {
        return new Subscriber<Object>() {
                @Override
                public void onCompleted() {
                    LOG.debug("outound meet onCompleted for {}", HttpConnection.this);
                    _op.onOutboundCompleted(HttpConnection.this);
                }

                @Override
                public void onError(final Throwable e) {
                    if (!(e instanceof CloseException)) {
                        LOG.warn("outbound meet onError with ({}), try close {}",
                                ExceptionUtils.exception2detail(e), HttpConnection.this);
                    }
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object obj) {
                    handleOutobj(obj);
                }};
    }

    private void handleOutobj(final Object obj) {
        if (obj instanceof Stepable) {
            LOG.debug("handle ({}) as Nextable for {}", obj, HttpConnection.this);
            handleNextable((Stepable<?>)obj);
        } else {
            LOG.debug("handle ({}) as sending msg for {}", obj, HttpConnection.this);
            _op.sendOutmsg(HttpConnection.this, obj);
        }
    }

    private void handleNextable(final Stepable<?> stepable) {
        sendElementAndFetchNext(stepable, stepable.element() instanceof Observable ? (Observable<?>) stepable.element()
                : Observable.just(stepable.element()));
    }

    private void sendElementAndFetchNext(final Stepable<?> stepable, final Observable<?> element) {
        element.subscribe(new Observer<Object>() {
            @Override
            public void onCompleted() {
                flushThenStep(stepable);
            }

            @Override
            public void onError(final Throwable e) {
                if (!(e instanceof CloseException)) {
                    LOG.warn("outbound unit({})'s element invoke onError with ({}), try close {}",
                            stepable, ExceptionUtils.exception2detail(e), HttpConnection.this);
                }
                fireClosed(e);
            }

            @Override
            public void onNext(final Object obj) {
                handleOutobj(obj);
            }});
    }

    private void flushThenStep(final Stepable<?> stepable) {
        _op.flush(HttpConnection.this).subscribe(new Action0() {
            @Override
            public void call() {
                stepable.step();
            }
        }, new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                if (!(e instanceof CloseException)) {
                    LOG.warn("outbound unit({})'s flush meet onError with ({}), try close {}",
                            stepable, ExceptionUtils.exception2detail(e), HttpConnection.this);
                }
                fireClosed(e);
            }});
    }

    private Subscription buildPlaceholderSubscription() {
        return new Subscription() {
            @Override
            public void unsubscribe() {
            }
            @Override
            public boolean isUnsubscribed() {
                return true;
            }};
    }

    private void unsubscribeOutbound() {
        final Subscription subscription = outboundSubscriptionUpdater.getAndSet(this, null);
        if (null != subscription && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
    }

    protected Subscription setOutbound(final Observable<? extends Object> message) {
        return this._op.setOutbound(this, message);
    }

    protected abstract void onInboundMessage(final HttpObject inmsg);

    protected abstract void onInboundCompleted();

    protected abstract void beforeSendingOutbound(final Object outmsg);

    protected abstract void onOutboundCompleted();

    protected abstract void onChannelInactive();

    private volatile long _unreadBegin = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<HttpConnection> readBeginUpdater =
            AtomicLongFieldUpdater.newUpdater(HttpConnection.class, "_readBegin");

    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;

    //  标记在当前 inboundHandler 期间是否处理过 inmsg
    private volatile boolean _inmsgRecvd = false;

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

    protected interface ConnectionOp {
        public boolean attachInbound(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber);

        public void onInmsgRecvd(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber,
                final HttpObject msg);

        public Subscription setOutbound(final HttpConnection<?> connection, final Observable<? extends Object> outbound);

        public void sendOutmsg(final HttpConnection<?> connection, final Object msg);

        public Completable flush(final HttpConnection<?> connection);

        public void onOutboundCompleted(final HttpConnection<?> connection);

        public void readMessage(final HttpConnection<?> connection);

        public void setWriteBufferWaterMark(final HttpConnection<?> connection, final int low, final int high);

        public boolean isWritable(final HttpConnection<?> connection);

        public Future<?> runAtEventLoop(final HttpConnection<?> connection, final Runnable task);
    }

    private static final ConnectionOp WHEN_ACTIVE = new ConnectionOp() {
        @Override
        public boolean attachInbound(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber) {
            return connection.holdInboundAndInstallHandler(subscriber);
        }

        @Override
        public void onInmsgRecvd(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber,
                final HttpObject inmsg) {
            connection.processInmsg(subscriber, inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpConnection<?> connection, final Observable<? extends Object> outbound) {
            return connection.doSetOutbound(outbound);
        }

        @Override
        public void sendOutmsg(final HttpConnection<?> connection, final Object msg) {
            connection.doSendOutmsg(msg);
        }

        @Override
        public Completable flush(final HttpConnection<?> connection) {
            return connection.doFlush();
        }

        @Override
        public void onOutboundCompleted(final HttpConnection<?> connection) {
            connection.onOutboundCompleted();
        }

        @Override
        public void readMessage(final HttpConnection<?> connection) {
            connection.doReadMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final HttpConnection<?> connection, final int low, final int high) {
            connection._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            LOG.info("channel({}) setWriteBufferWaterMark with low:{} high:{}", connection._channel, low, high);
        }

        @Override
        public boolean isWritable(final HttpConnection<?> connection) {
            return connection._channel.isWritable();
        }

        @Override
        public Future<?> runAtEventLoop(final HttpConnection<?> connection, final Runnable task) {
            return connection._channel.eventLoop().submit(task);
        }
    };

    private static final ConnectionOp WHEN_UNACTIVE = new ConnectionOp() {
        @Override
        public boolean attachInbound(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onError(new RuntimeException(connection + " has terminated."));
            }
            return false;
        }

        @Override
        public void onInmsgRecvd(
                final HttpConnection<?> connection,
                final Subscriber<? super DisposableWrapper<? extends HttpObject>> subscriber,
                final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            LOG.warn("{} has terminated, onInmsgRecvd and just release msg({}).", connection, inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpConnection<?> connection, final Observable<? extends Object> outbound) {
            LOG.warn("{} has terminated, ignore setOutbound({})", connection, outbound);
            return null;
        }

        @Override
        public void sendOutmsg(final HttpConnection<?> connection, final Object outmsg) {
            LOG.warn("{} has terminated, ignore send outmsg({})", connection, outmsg);
        }

        @Override
        public void onOutboundCompleted(final HttpConnection<?> connection) {
            LOG.warn("{} has terminated, ignore onOutmsgCompleted event", connection);
        }

        @Override
        public Completable flush(final HttpConnection<?> connection) {
            LOG.warn("{} has terminated, ignore flush event", connection);
            return Completable.error(new RuntimeException(connection + " has terminated"));
        }

        @Override
        public void readMessage(final HttpConnection<?> connection) {
            LOG.warn("{} has terminated, ignore read message action", connection);
        }

        @Override
        public void setWriteBufferWaterMark(final HttpConnection<?> connection, final int low, final int high) {
            LOG.warn("{} has terminated, ignore setWriteBufferWaterMark({}/{})", connection, low, high);
        }

        @Override
        public boolean isWritable(final HttpConnection<?> connection) {
            LOG.warn("{} has terminated, ignore call isWritable", connection);
            return false;
        }

        @Override
        public Future<?> runAtEventLoop(final HttpConnection<?> connection, final Runnable task) {
            LOG.warn("{} has terminated, ignore runAtEventLoop with ({})", connection, task);
            return null;
        }
    };
}
