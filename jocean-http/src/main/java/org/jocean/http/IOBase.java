package org.jocean.http;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.ReadPolicy.Intraffic;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.DisposableWrapper;
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
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import rx.Observable;
import rx.Observer;
import rx.Single;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.subscriptions.Subscriptions;

public abstract class IOBase<T> implements Inbound, Outbound, TerminateAware<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(IOBase.class);
    
    protected final InterfaceSelector _selector = new InterfaceSelector();
    
    protected IOBase(final Channel channel) {
        
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
    
//    @Override
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
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate((T) this, onTerminate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Action0 doOnTerminate(Action1<T> onTerminate) {
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
                _iobaseop.setWriteBufferWaterMark(IOBase.this, low, high);
            }

            @Override
            public Observable<Boolean> writability() {
                return Observable.unsafeCreate(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(final Subscriber<? super Boolean> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            _iobaseop.runAtEventLoop(IOBase.this, new Runnable() {
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
            subscriber.onNext(this._iobaseop.isWritable(this));
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
    
    private final COWCompositeSupport<Subscriber<? super Boolean>> _writabilityObserver = 
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
                } catch (Exception e) {
                    LOG.warn("exception when invoke onNext({}), detail: {}",
                        subscriber,
                        ExceptionUtils.exception2detail(e));
                }
            }
        }};

    private void onWritabilityChanged() {
        this._writabilityObserver.foreachComponent(ON_WRITABILITY_CHGED, this._iobaseop.isWritable(this));
    }

    protected void sendOutmsg(final Object outmsg) {
        if (outmsg instanceof DoFlush) {
            this._channel.flush();
        } else {
            sendOutbound(outmsg)
            .addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future)
                        throws Exception {
                    if (future.isSuccess()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("send http outmsg {} success.", outmsg);
                        }
                        onOutboundMsgSended(outmsg);
                    } else {
                        LOG.warn("exception when send outmsg: {}, detail: {}",
                                outmsg, ExceptionUtils.exception2detail(future.cause()));
                        fireClosed(new TransportException("send outmsg error", future.cause()));
                    }
                }});
        }
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

    private ChannelFuture sendOutbound(Object outmsg) {
        while (outmsg instanceof DisposableWrapper) {
            outmsg = ((DisposableWrapper<?>)outmsg).unwrap();
        }
        return this._isFlushPerWrite
                ? this._channel.writeAndFlush(ReferenceCountUtil.retain(outmsg))
                : this._channel.write(ReferenceCountUtil.retain(outmsg));
    }
    
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

    private void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (needRead()) {
            final Single<?> when = this._whenToRead;
            if (null != when) {
                final Subscription pendingRead = when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        _iobaseop.readMessage(IOBase.this);
                    }});

                pendingReadUpdater.set(this, pendingRead);
            } else {
                //  perform read at once
                _iobaseop.readMessage(IOBase.this);
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
    
    private Intraffic buildIntraffic() {
        return new Intraffic() {
            @Override
            public long durationFromRead() {
                final long begin = _unreadBegin;
                return 0 == begin ? 0 : System.currentTimeMillis() - begin;
            }
            
            @Override
            public long durationFromBegin() {
                return Math.max(System.currentTimeMillis() - readBeginUpdater.get(IOBase.this), 1L);
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
    
    protected SimpleChannelInboundHandler<HttpObject> buildInboundHandler(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        return new SimpleChannelInboundHandler<HttpObject>(false) {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject inmsg) throws Exception {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("IOBase: channel({})/handler({}): channelRead0 and call with msg({}).",
                        ctx.channel(), ctx.name(), inmsg);
                }
                _iobaseop.inboundOnNext(IOBase.this, subscriber, inmsg);
            }};
    }

    protected Observer<Object> buildOutboundObserver() {
        return new Observer<Object>() {
                @Override
                public void onCompleted() {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("outound invoke onCompleted for iobase: {}", IOBase.this);
                    }
                    _iobaseop.outboundOnCompleted(IOBase.this);
                }

                @Override
                public void onError(final Throwable e) {
                    if (!(e instanceof CloseException)) {
                        LOG.warn("outound invoke onError with ({}), try close iobase: {}",
                                ExceptionUtils.exception2detail(e), IOBase.this);
                    }
                    fireClosed(e);
                }

                @Override
                public void onNext(final Object outmsg) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("outbound invoke onNext({}) for iobase: {}", outmsg, IOBase.this);
                    }
                    _iobaseop.outboundOnNext(IOBase.this, outmsg);
                }};
    }
    
    protected void fireClosed(final Throwable e) {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this, e);
    }

    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((IOBase<?>)args[0]).doClosed((Throwable)args[1]);
        }};

    protected void setInboundHandler(final ChannelHandler handler) {
        inboundHandlerUpdater.set(this, handler);
    }

    protected void removeInboundHandler() {
        final ChannelHandler handler = inboundHandlerUpdater.getAndSet(this, null);
        if (null != handler) {
            Nettys.actionToRemoveHandler(this._channel, handler).call();
        }
    }
        
    protected void setOutboundSubscription(final Subscription subscription) {
        outboundSubscriptionUpdater.set(this, subscription);
    }
    
    protected void unsubscribeOutbound() {
        final Subscription subscription = outboundSubscriptionUpdater.getAndSet(this, null);
        if (null != subscription && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
    }
    
    protected abstract void doClosed(final Throwable e);
    
    protected abstract boolean needRead();

    protected abstract void inboundOnNext(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
            final HttpObject inmsg);

    protected abstract void outboundOnNext(final Object outmsg);
    
    protected abstract void outboundOnCompleted();
    
    protected abstract void onChannelInactive();
    
    private volatile Single<?> _whenToRead = null;
    
    private volatile long _unreadBegin = 0;

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<IOBase> readBeginUpdater = 
            AtomicLongFieldUpdater.newUpdater(IOBase.class, "_readBegin");
    
    @SuppressWarnings("unused")
    private volatile long _readBegin = 0;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<IOBase, Subscription> pendingReadUpdater =
            AtomicReferenceFieldUpdater.newUpdater(IOBase.class, Subscription.class, "_pendingRead");
    
    @SuppressWarnings("unused")
    private volatile Subscription _pendingRead = null;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<IOBase, ChannelHandler> inboundHandlerUpdater =
            AtomicReferenceFieldUpdater.newUpdater(IOBase.class, ChannelHandler.class, "_inboundHandler");
    
    @SuppressWarnings("unused")
    private volatile ChannelHandler _inboundHandler;
    
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<IOBase, Subscription> outboundSubscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(IOBase.class, Subscription.class, "_outboundSubscription");
    
    @SuppressWarnings("unused")
    private volatile Subscription _outboundSubscription;
    
    protected interface IOBaseOp {
        public void inboundOnNext(
                final IOBase<?> io,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject msg);
        
        public void outboundOnNext(final IOBase<?> io, final Object msg);

        public void outboundOnCompleted(final IOBase<?> io);
        
        public void readMessage(final IOBase<?> io);

        public void setWriteBufferWaterMark(final IOBase<?> io, final int low, final int high);
        
        public boolean isWritable(final IOBase<?> io);
        
        public Future<?> runAtEventLoop(final IOBase<?> io, final Runnable task);
    }
    
    private static final IOBaseOp IOOP_ACTIVE = new IOBaseOp() {
        @Override
        public void inboundOnNext(
                final IOBase<?> iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject msg) {
            iobase.inboundOnNext(subscriber, msg);
        }
        
        @Override
        public void outboundOnNext(
                final IOBase<?> iobase,
                final Object msg) {
            iobase.outboundOnNext(msg);
        }
        
        @Override
        public void outboundOnCompleted(
                final IOBase<?> iobase) {
            iobase.outboundOnCompleted();
        }
        
        @Override
        public void readMessage(final IOBase<?> iobase) {
            iobase.readMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final IOBase<?> iobase, final int low, final int high) {
            iobase._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            if (LOG.isInfoEnabled()) {
                LOG.info("channel({}) setWriteBufferWaterMark with low:{} high:{}", iobase._channel, low, high);
            }
        }
        
        @Override
        public boolean isWritable(final IOBase<?> iobase) {
            return iobase._channel.isWritable();
        }
        
        @Override
        public Future<?> runAtEventLoop(final IOBase<?> iobase, final Runnable task) {
            return iobase._channel.eventLoop().submit(task);
        }
    };
    
    private static final IOBaseOp IOOP_UNACTIVE = new IOBaseOp() {
        @Override
        public void inboundOnNext(
                final IOBase<?> iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOBase(inactive): channelRead0 and release msg({}).", inmsg);
            }
        }
        
        @Override
        public void outboundOnNext(final IOBase<?> iobase, final Object msg) {
        }
        
        @Override
        public void outboundOnCompleted(final IOBase<?> iobase) {
        }
        
        @Override
        public void readMessage(final IOBase<?> iobase) {
        }

        @Override
        public void setWriteBufferWaterMark(final IOBase<?> iobase, final int low, final int high) {
        }
        
        @Override
        public boolean isWritable(final IOBase<?> iobase) {
            return false;
        }
        
        @Override
        public Future<?> runAtEventLoop(final IOBase<?> iobase, final Runnable task) {
            return null;
        }
    };
}
