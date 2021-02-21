package org.jocean.http.server.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.CloseException;
import org.jocean.http.DoFlush;
import org.jocean.http.HttpSlice;
import org.jocean.http.Inbound;
import org.jocean.http.Outbound;
import org.jocean.http.TrafficCounter;
import org.jocean.http.TransportException;
import org.jocean.http.WriteCtrl;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.HaltAware;
import org.jocean.idiom.HaltAwareSupport;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.Stepable;
import org.jocean.idiom.rx.Action1_N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import rx.Completable;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.subscriptions.Subscriptions;

public abstract class HttpTradeConnection<T> implements Inbound, Outbound, AutoCloseable, HaltAware<T> {
    private static final Logger LOG = LoggerFactory.getLogger(HttpTradeConnection.class);

    protected final InterfaceSelector _selector = new InterfaceSelector();

    protected HttpTradeConnection(final Channel channel) {

        this._haltSupport = new HaltAwareSupport<T>(this._selector);

        this._channel = channel;
        this._op = this._selector.build(ConnectionOp.class, WHEN_ACTIVE, WHEN_UNACTIVE);
        this._traffic = Nettys.applyToChannel(onHalt(),
                this._channel,
                HttpHandlers.TRAFFICCOUNTER);

        Nettys.applyToChannel(onHalt(),
                channel,
                HttpHandlers.ON_EXCEPTION_CAUGHT,
                (Action1<Throwable>)cause -> fireClosed(cause));

        Nettys.applyToChannel(onHalt(),
                channel,
                HttpHandlers.ON_CHANNEL_INACTIVE,
                (Action0)() -> onChannelInactive());

        Nettys.applyToChannel(onHalt(),
                channel,
                HttpHandlers.ON_CHANNEL_READCOMPLETE,
                (Action0)() -> onReadComplete());

        Nettys.applyToChannel(onHalt(),
                channel,
                HttpHandlers.ON_CHANNEL_WRITABILITYCHANGED,
                (Action0)() -> onWritabilityChanged());

        if (!this._channel.isActive()) {
            fireClosed(new TransportException("channelInactive of " + channel));
        } else {
            // added 2021-02-17
            Nettys.applyToChannel(onHalt(),
                    channel,
                    HttpHandlers.ON_MESSAGE,
                    buildInboundHandler());
        }
    }

    private final ConnectionOp _op;

    protected final Channel _channel;

    private volatile boolean _isFlushPerWrite = false;

    private final TrafficCounter _traffic;

    protected final HaltAwareSupport<T> _haltSupport;

    protected final AtomicInteger _contentSize = new AtomicInteger();
    protected final StringBuilder _readTracing = new StringBuilder();

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
    public Action1<Action0> onHalt() {
        return this._haltSupport.onHalt((T) this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action1<Action1<T>> onHaltOf() {
        return this._haltSupport.onHaltOf((T) this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action0 doOnHalt(final Action0 onhalt) {
        return this._haltSupport.doOnHalt((T) this, onhalt);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action0 doOnHalt(final Action1<T> onhalt) {
        return this._haltSupport.doOnHalt((T) this, onhalt);
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
                return Math.max(System.currentTimeMillis() - readBeginUpdater.get(HttpTradeConnection.this), 1L);
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
                _op.setWriteBufferWaterMark(HttpTradeConnection.this, low, high);
            }

            @Override
            public Observable<Boolean> writability() {
                return Observable.unsafeCreate(subscriber -> {
                        if (!subscriber.isUnsubscribed()) {
                            _op.runAtEventLoop(HttpTradeConnection.this, ()-> addWritabilitySubscriber(subscriber));
                        }
                });
            }

            @Override
            public Observable<Object> sending() {
                return Observable.unsafeCreate(subscriber -> addSendingSubscriber(subscriber));
            }

            @Override
            public Observable<Object> sended() {
                return Observable.unsafeCreate(subscriber -> addSendedSubscriber(subscriber));
            }};
    }

    Observable<HttpSlice> received() {
        return Observable.unsafeCreate(subscriber -> addReceivedSubscriber(subscriber));
    }

    private void addWritabilitySubscriber(final Subscriber<? super Boolean> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            subscriber.onNext(this._op.isWritable(this));
            this._writabilityObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(() -> _writabilityObserver.removeComponent(subscriber)));
        }
    }

    private void addSendingSubscriber(final Subscriber<? super Object> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._sendingObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(() -> _sendingObserver.removeComponent(subscriber)));
        }
    }

    private void addSendedSubscriber(final Subscriber<? super Object> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._sendedObserver.addComponent(subscriber);
            subscriber.add(Subscriptions.create(() -> _sendedObserver.removeComponent(subscriber)));
        }
    }

    private void addReceivedSubscriber(final Subscriber<? super HttpSlice> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            if (this._receivedCompleted) {
                // 已经结束了
                LOG.debug("addReceivedSubscriber: call {}'s onCompleted()", subscriber);
                subscriber.onCompleted();
            } else {
                this._receivedObserver.addComponent(subscriber);
                LOG.debug("addReceivedSubscriber: addComponent {}", subscriber);
                subscriber.add(Subscriptions.create(() -> {
                    LOG.debug("addReceivedSubscriber: removeComponent {}", subscriber);
                    _receivedObserver.removeComponent(subscriber);
                }));
            }
        }
    }

    private final COWCompositeSupport<Subscriber<? super Boolean>> _writabilityObserver = new COWCompositeSupport<>();
    private final COWCompositeSupport<Subscriber<? super Object>> _sendingObserver = new COWCompositeSupport<>();
    private final COWCompositeSupport<Subscriber<? super Object>> _sendedObserver = new COWCompositeSupport<>();
    private final COWCompositeSupport<Subscriber<? super HttpSlice>> _receivedObserver = new COWCompositeSupport<>();

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

    private static final Action1_N<Subscriber<? super Object>> OBJ_ON_COMPLETED = new Action1_N<Subscriber<? super Object>>() {
        @Override
        public void call(final Subscriber<? super Object> subscriber, final Object... args) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onCompleted();
                } catch (final Exception e) {
                    LOG.warn("exception when invoke onCompleted({}), detail: {}", subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }};

    // TODO, when to invoke OBJ_ON_ERROR
    private static final Action1_N<Subscriber<? super Object>> OBJ_ON_ERROR = new Action1_N<Subscriber<? super Object>>() {
        @Override
        public void call(final Subscriber<? super Object> subscriber, final Object... args) {
            final Throwable e = (Throwable)args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onError(e);
                } catch (final Exception e1) {
                    LOG.warn("exception when invoke onError({}), detail: {}", subscriber, ExceptionUtils.exception2detail(e1));
                }
            }
        }};

    private void onReadComplete() {
        _readTracing.append("|RC|");

        if (null != this._inmsgsRef.get()) {
            //  如 _inmsgsRef 已经被赋值有 List<?> 实例，则已经解码出有效 inmsg
            this._unreadBegin = System.currentTimeMillis();
            _readTracing.append("ON|");
            this._receivedObserver.foreachComponent(RECEIVED_ON_NEXT, currentSlice(!this._receivedCompleted));

            if (this._receivedCompleted) {
                _readTracing.append("OC|");
                this._receivedObserver.foreachComponent(RECEIVED_ON_COMPLETED);
            }
        }
        else {
            //  SSL enabled 连接:
            //  收到 READ COMPLETE 事件时，可能会由于当前接收到的数据不够，还未能解出有效的 inmsg ，
            //  此时应继续调用 channel.read(), 来继续获取对端发送来的 加密数据
            LOG.debug("!NO! inmsg received, continue read for {}", this);
            _readTracing.append("NR|");
            readMessage();
        }
    }

    private static final Action1_N<Subscriber<? super HttpSlice>> RECEIVED_ON_NEXT = (Action1_N<Subscriber<? super HttpSlice>>)
        (subscriber, args) -> {
            final HttpSlice httpSlice = (HttpSlice)args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    LOG.debug("RECEIVED_ON_NEXT: call {}'s onNext", subscriber);
                    subscriber.onNext(httpSlice);
                } catch (final Exception e) {
                    LOG.warn("exception when invoke HttpSlice's onNext({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        };

    private static final Action1_N<Subscriber<? super HttpSlice>> RECEIVED_ON_COMPLETED = (Action1_N<Subscriber<? super HttpSlice>>)
        (subscriber, args) -> {
            if (!subscriber.isUnsubscribed()) {
                try {
                    LOG.debug("RECEIVED_ON_COMPLETED: call {}'s onCompleted", subscriber);
                    subscriber.onCompleted();
                } catch (final Exception e) {
                    LOG.warn("exception when invoke HttpSlice's onCompleted({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        };

    private static final Action1_N<Subscriber<? super HttpSlice>> RECEIVED_ON_ERROR = (Action1_N<Subscriber<? super HttpSlice>>)
        (subscriber, args) -> {
            final Throwable e = (Throwable)args[0];
            if (!subscriber.isUnsubscribed()) {
                try {
                    LOG.debug("RECEIVED_ON_ERROR: call {}'s onError", subscriber);
                    subscriber.onError(e);
                } catch (final Exception e1) {
                    LOG.warn("exception when invoke HttpSlice's onError({}), detail: {}", subscriber, ExceptionUtils.exception2detail(e1));
                }
            }
        };

    private HttpSlice currentSlice(final boolean needStep) {
        final Subscription runOnce = needStep ? Subscriptions.create(()->readMessage()) : Subscriptions.unsubscribed();

        final List<DisposableWrapper<HttpObject>> inmsgs = this._inmsgsRef.getAndSet(null);

        LOG.debug("build HttpSlice ({} element) with need step {}", inmsgs.size(), needStep);

        return new HttpSlice() {
            @Override
            public String toString() {
                final int maxLen = 10;
                return new StringBuilder().append("HttpSlice [step=")
                        .append(runOnce.isUnsubscribed() ? "called" : "uncall").append(",element=")
                        .append(inmsgs.subList(0, Math.min(inmsgs.size(), maxLen))).append("]").toString();
            }
            @Override
            public void step() {
                runOnce.unsubscribe();
            }
            @Override
            public Iterable<? extends DisposableWrapper<? extends HttpObject>> element() {
                return inmsgs;
            }};
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

    private void doReadMessage() {
        LOG.debug("trigger read message for {}", this);
        _readTracing.append("|RM|");
        this._channel.read();
        this._unreadBegin = 0;
        readBeginUpdater.compareAndSet(this, 0, System.currentTimeMillis());
    }

    private void invokeInboundOnError(final Throwable error) {
        this._receivedObserver.foreachComponent(RECEIVED_ON_ERROR, error);
    }

    private SimpleChannelInboundHandler<HttpObject> buildInboundHandler() {
        return new SimpleChannelInboundHandler<HttpObject>(false) {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject inmsg) throws Exception {
                LOG.debug("channelRead0 income msg({}) for {}", inmsg, HttpTradeConnection.this);
                _op.onInmsgRecvd(HttpTradeConnection.this, inmsg);
            }};
    }

    private void processInmsg(final HttpObject inmsg) {

        if (inmsg instanceof HttpMessage) {
            _contentSize.set(0);
            _readTracing.setLength(0);
            _receivedCompleted = false;
        }

        if (inmsg instanceof HttpContent) {
            final int readableBytes = ((HttpContent)inmsg).content().readableBytes();
            _contentSize.addAndGet(readableBytes);
            _readTracing.append("R:");
            _readTracing.append(readableBytes);
        }

        onInboundMessage(inmsg);

        newOrFetchInmsgs().add(DisposableWrapperUtil.disposeOn(this, RxNettys.wrap4release(inmsg)));

        if (inmsg instanceof HttpResponse) {
            this._currentStatus = ((HttpResponse)inmsg).status().code();
        }

        if (inmsg instanceof LastHttpContent) {
            _readTracing.append("|LC|");
            // -1 == _currentStatus means never meet HttpResponse
            if (-1 == this._currentStatus || this._currentStatus >= 200) {
                LOG.debug("recv LastHttpContent({}), try to unholdInboundAndUninstallHandler and onInboundCompleted", inmsg);

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
                this._receivedCompleted = true;
            } else {
                _readTracing.append("SKP|");
                LOG.info("recv LastHttpContent({}) for current status {}, skip and continue recv next response", inmsg, this._currentStatus);
            }
        }
    }

    private List<DisposableWrapper<HttpObject>> newOrFetchInmsgs() {
        final List<DisposableWrapper<HttpObject>> inmsgs = this._inmsgsRef.get();
        if ( null != inmsgs) {
            return inmsgs;
        } else {
            this._inmsgsRef.set(new ArrayList<DisposableWrapper<HttpObject>>());
            return this._inmsgsRef.get();
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
                    LOG.debug("send outmsg({}) success for {}", outmsg, HttpTradeConnection.this);
                    notifySendedOnNext(outmsg);
                } else {
                    LOG.warn("exception when send outmsg({}) for {}, detail: {}",
                            outmsg, HttpTradeConnection.this, ExceptionUtils.exception2detail(future.cause()));
                    fireClosed(new TransportException("send outmsg error", future.cause()));
                }
            }};
        notifySendingOnNext(outmsg);
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

    private void notifySendingOnNext(final Object outmsg) {
        this._sendingObserver.foreachComponent(OBJ_ON_NEXT, outmsg);
    }

    private void notifySendingOnCompleted() {
        this._sendingObserver.foreachComponent(OBJ_ON_COMPLETED);
    }

    private void notifySendedOnNext(final Object outmsg) {
        this._sendedObserver.foreachComponent(OBJ_ON_NEXT, outmsg);
    }

    private void notifySendedOnCompleted() {
        this._sendedObserver.foreachComponent(OBJ_ON_COMPLETED);
    }

    protected void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((HttpTradeConnection<?>)args[0]).doClosed((Throwable)args[1]);
        }
    };

    @SuppressWarnings("unchecked")
    private void doClosed(final Throwable e) {
        LOG.debug("closing {}, cause by {}", toString(), errorAsString(e));

        // notify inbound Subscriber by error
        invokeInboundOnError(e);

        unsubscribeOutbound();

        // clear cached inbound part
        final List<DisposableWrapper<HttpObject>> inmsgs = this._inmsgsRef.getAndSet(null);
        if (null != inmsgs) {
            inmsgs.clear();
        }

        //  fire all pending subscribers onError with unactived exception
        this._haltSupport.fireAllActions((T) this);
    }

    protected static String errorAsString(final Throwable e) {
        return e != null ? (e instanceof CloseException) ? "close()" : ExceptionUtils.exception2detail(e) : "no error";
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
            outboundSubscriber.add(Subscriptions.create(() -> {
                    if (outboundSubscriptionUpdater.compareAndSet(HttpTradeConnection.this, subscription, null)) {
                        LOG.debug("reset _outboundSubscription to null");
                    } else {
                        LOG.debug("_outboundSubscription has changed, MAYBE force replace to next outbound, ignore reset");
                    }
                }));
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
                    LOG.debug("outound invoke onCompleted event for {}", HttpTradeConnection.this);
                    notifySendingOnCompleted();
                    onOutboundCompleted();
                }

                @Override
                public void onError(final Throwable e) {
                    if (!(e instanceof CloseException)) {
                        LOG.warn("outbound invoke onError event with ({}), try close {}",
                                ExceptionUtils.exception2detail(e), HttpTradeConnection.this);
                    }
                    // TODO
                    // onOutmsgError(e);
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object obj) {
                    handleOutobj(obj);
                }};
    }

    private void onOutboundCompleted() {
        // force flush for _isFlushPerWrite = false
        //  reference: https://github.com/netty/netty/commit/789e323b79d642ea2c0a024cb1c839654b7b8fad
        //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
        //  Detail:
        //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
        //
        //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        //
        // See https://github.com/netty/netty/issues/2983 for more information.
        this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(future -> {
                if (future.isSuccess()) {
                    LOG.debug("all outmsg sended completed for {}", HttpTradeConnection.this);
                    notifySendedOnCompleted();
                } else {
                    //  TODO
                    // fireClosed(new TransportException("flush response error", future.cause()));
                }
            });
    }

    private void handleOutobj(final Object obj) {
        if (obj instanceof Stepable) {
            LOG.debug("handle ({}) as Stepable for {}", obj, HttpTradeConnection.this);
            handleStepable((Stepable<?>)obj);
        } else {
            LOG.debug("handle ({}) as sending msg for {}", obj, HttpTradeConnection.this);
            _op.sendOutmsg(HttpTradeConnection.this, obj);
        }
    }

    private void handleStepable(final Stepable<?> stepable) {
        sendElementAndFetchNext(stepable, element2observable(stepable.element()));
    }

    private Observable<?> element2observable(final Object element) {
        return element instanceof Observable ? (Observable<?>) element
                : (element instanceof Iterable ? Observable.from((Iterable<?>)element) : Observable.just(element) );
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
                            stepable, ExceptionUtils.exception2detail(e), HttpTradeConnection.this);
                }
                fireClosed(e);
            }

            @Override
            public void onNext(final Object obj) {
                handleOutobj(obj);
            }});
    }

    private void flushThenStep(final Stepable<?> stepable) {
        _op.flush(HttpTradeConnection.this).subscribe(() -> stepable.step(),  e -> {
                if (!(e instanceof CloseException)) {
                    LOG.warn("outbound unit({})'s flush meet onError with ({}), try close {}",
                            stepable, ExceptionUtils.exception2detail(e), HttpTradeConnection.this);
                }
                fireClosed(e);
            });
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

    protected abstract void beforeSendingOutbound(final Object outmsg);

    protected abstract void onChannelInactive();

    private volatile int _currentStatus = -1;
    private volatile boolean _receivedCompleted = false;

    private volatile long _unreadBegin = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<HttpTradeConnection> readBeginUpdater = AtomicLongFieldUpdater.newUpdater(HttpTradeConnection.class, "_readBegin");

    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;

    private final AtomicReference<List<DisposableWrapper<HttpObject>>> _inmsgsRef = new AtomicReference<>();

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<HttpTradeConnection, Subscription> outboundSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HttpTradeConnection.class, Subscription.class, "_outboundSubscription");

    @SuppressWarnings("unused")
    private volatile Subscription _outboundSubscription;

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<HttpTradeConnection> transactionUpdater =
            AtomicIntegerFieldUpdater.newUpdater(HttpTradeConnection.class, "_transactionStatus");

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
        public void onInmsgRecvd(final HttpTradeConnection<?> connection, final HttpObject msg);

        public Subscription setOutbound(final HttpTradeConnection<?> connection, final Observable<? extends Object> outbound);

        public void sendOutmsg(final HttpTradeConnection<?> connection, final Object msg);

        public Completable flush(final HttpTradeConnection<?> connection);

        public void readMessage(final HttpTradeConnection<?> connection);

        public void setWriteBufferWaterMark(final HttpTradeConnection<?> connection, final int low, final int high);

        public boolean isWritable(final HttpTradeConnection<?> connection);

        public Future<?> runAtEventLoop(final HttpTradeConnection<?> connection, final Runnable task);
    }

    private static final ConnectionOp WHEN_ACTIVE = new ConnectionOp() {
        @Override
        public void onInmsgRecvd(final HttpTradeConnection<?> connection, final HttpObject inmsg) {
            connection.processInmsg(inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpTradeConnection<?> connection, final Observable<? extends Object> outbound) {
            return connection.doSetOutbound(outbound);
        }

        @Override
        public void sendOutmsg(final HttpTradeConnection<?> connection, final Object msg) {
            connection.doSendOutmsg(msg);
        }

        @Override
        public Completable flush(final HttpTradeConnection<?> connection) {
            return connection.doFlush();
        }

        @Override
        public void readMessage(final HttpTradeConnection<?> connection) {
            connection.doReadMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final HttpTradeConnection<?> connection, final int low, final int high) {
            connection._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            LOG.trace("channel({}) setWriteBufferWaterMark with low:{} high:{}", connection._channel, low, high);
        }

        @Override
        public boolean isWritable(final HttpTradeConnection<?> connection) {
            return connection._channel.isWritable();
        }

        @Override
        public Future<?> runAtEventLoop(final HttpTradeConnection<?> connection, final Runnable task) {
            return connection._channel.eventLoop().submit(task);
        }
    };

    private static final ConnectionOp WHEN_UNACTIVE = new ConnectionOp() {
        @Override
        public void onInmsgRecvd(final HttpTradeConnection<?> connection, final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            LOG.warn("{} has terminated, onInmsgRecvd and just release msg({}).", connection, inmsg);
        }

        @Override
        public Subscription setOutbound(final HttpTradeConnection<?> connection, final Observable<? extends Object> outbound) {
            LOG.warn("{} has terminated, ignore setOutbound({})", connection, outbound);
            return null;
        }

        @Override
        public void sendOutmsg(final HttpTradeConnection<?> connection, final Object outmsg) {
            LOG.warn("{} has terminated, ignore send outmsg({})", connection, outmsg);
        }

        @Override
        public Completable flush(final HttpTradeConnection<?> connection) {
            LOG.warn("{} has terminated, ignore flush event", connection);
            return Completable.error(new RuntimeException(connection + " has terminated"));
        }

        @Override
        public void readMessage(final HttpTradeConnection<?> connection) {
            LOG.warn("{} has terminated, ignore read message action", connection);
        }

        @Override
        public void setWriteBufferWaterMark(final HttpTradeConnection<?> connection, final int low, final int high) {
            LOG.warn("{} has terminated, ignore setWriteBufferWaterMark({}/{})", connection, low, high);
        }

        @Override
        public boolean isWritable(final HttpTradeConnection<?> connection) {
            LOG.warn("{} has terminated, ignore call isWritable", connection);
            return false;
        }

        @Override
        public Future<?> runAtEventLoop(final HttpTradeConnection<?> connection, final Runnable task) {
            LOG.warn("{} has terminated, ignore runAtEventLoop with ({})", connection, task);
            return null;
        }
    };
}
