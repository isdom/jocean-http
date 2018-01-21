package org.jocean.http;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jocean.http.ReadPolicy.Intraffic;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.rx.Action1_N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import rx.Observable;
import rx.Single;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subscriptions.Subscriptions;

public abstract class IOBase implements Inbound, Outbound {
    private static final Logger LOG =
            LoggerFactory.getLogger(IOBase.class);
    
    protected final InterfaceSelector _selector = new InterfaceSelector();
    
    protected interface IOBaseOp {
        public void inboundOnNext(
                final IOBase io,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject msg);
        
        public void outboundOnNext(final IOBase io, final Object msg);

        public void outboundOnCompleted(final IOBase io);
        
        public void readMessage(final IOBase io);

        public void setWriteBufferWaterMark(final IOBase io, final int low, final int high);
        
        public boolean isWritable(final IOBase io);
        
        public Future<?> runAtEventLoop(final IOBase io, final Runnable task);
    }
    
    protected IOBase(final Channel channel) {
        this._channel = channel;
        this._iobaseop = this._selector.build(IOBaseOp.class, OP_ACTIVE, OP_UNACTIVE);
    }
    
    protected final IOBaseOp _iobaseop;
    
    protected final Channel _channel;
    
    private volatile boolean _isFlushPerWrite = false;
    
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
        
    protected void onWritabilityChanged() {
        this._writabilityObserver.foreachComponent(ON_WRITABILITY_CHGED, this._iobaseop.isWritable(this));
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

    protected void onOutboundMsgSended(final Object outmsg) {
        this._sendedObserver.foreachComponent(ON_SENDED, outmsg);
    }

    protected ChannelFuture sendOutbound(Object outmsg) {
        while (outmsg instanceof DisposableWrapper) {
            outmsg = ((DisposableWrapper<?>)outmsg).unwrap();
        }
        return this._isFlushPerWrite
                ? this._channel.writeAndFlush(ReferenceCountUtil.retain(outmsg))
                : this._channel.write(ReferenceCountUtil.retain(outmsg));
    }
    
    public void setReadPolicy(final ReadPolicy readPolicy) {
        runAtEventLoop0(new Runnable() {
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

    protected void onReadComplete() {
        this._unreadBegin = System.currentTimeMillis();
        if (needRead()) {
            final Single<?> when = this._whenToRead;
            if (null != when) {
                final Subscription pendingRead = when.subscribe(new Action1<Object>() {
                    @Override
                    public void call(final Object nouse) {
                        readMessage0();
                    }});

                pendingReadUpdater.set(this, pendingRead);
            } else {
                //  perform read at once
                readMessage0();
            }
        }
    }

    private void readMessage0() {
        this._iobaseop.readMessage(this);
    }
    
    private void runAtEventLoop0(final Runnable runnable) {
        this._iobaseop.runAtEventLoop(this, runnable);
    }
    
    protected abstract Intraffic buildIntraffic();
        
    protected abstract boolean needRead();

    protected abstract void inboundOnNext(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
            final HttpObject inmsg);

    protected abstract void outboundOnNext(final Object outmsg);
    
    protected abstract void outboundOnCompleted();
    
    protected abstract void readMessage();
    
    private volatile Single<?> _whenToRead = null;
    
    private static final AtomicReferenceFieldUpdater<IOBase, Subscription> pendingReadUpdater =
            AtomicReferenceFieldUpdater.newUpdater(IOBase.class, Subscription.class, "_pendingRead");
    
    @SuppressWarnings("unused")
    private volatile Subscription _pendingRead = null;
    
    protected volatile long _unreadBegin = 0;

    private static final IOBaseOp OP_ACTIVE = new IOBaseOp() {
        @Override
        public void inboundOnNext(
                final IOBase iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject msg) {
            iobase.inboundOnNext(subscriber, msg);
        }
        
        @Override
        public void outboundOnNext(
                final IOBase iobase,
                final Object msg) {
            iobase.outboundOnNext(msg);
        }
        
        @Override
        public void outboundOnCompleted(
                final IOBase iobase) {
            iobase.outboundOnCompleted();
        }
        
        @Override
        public void readMessage(final IOBase iobase) {
            iobase.readMessage();
        }

        @Override
        public void setWriteBufferWaterMark(final IOBase iobase, final int low, final int high) {
            iobase._channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
            if (LOG.isInfoEnabled()) {
                LOG.info("channel({}) setWriteBufferWaterMark with low:{} high:{}", iobase._channel, low, high);
            }
        }
        
        @Override
        public boolean isWritable(final IOBase iobase) {
            return iobase._channel.isWritable();
        }
        
        @Override
        public Future<?> runAtEventLoop(final IOBase iobase, final Runnable task) {
            return iobase._channel.eventLoop().submit(task);
        }
    };
    
    private static final IOBaseOp OP_UNACTIVE = new IOBaseOp() {
        @Override
        public void inboundOnNext(
                final IOBase iobase,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
                final HttpObject inmsg) {
            ReferenceCountUtil.release(inmsg);
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOBase(inactive): channelRead0 and release msg({}).", inmsg);
            }
        }
        
        @Override
        public void outboundOnNext(final IOBase iobase, final Object msg) {
        }
        
        @Override
        public void outboundOnCompleted(final IOBase iobase) {
        }
        
        @Override
        public void readMessage(final IOBase iobase) {
        }

        @Override
        public void setWriteBufferWaterMark(final IOBase iobase, final int low, final int high) {
        }
        
        @Override
        public boolean isWritable(final IOBase iobase) {
            return false;
        }
        
        @Override
        public Future<?> runAtEventLoop(final IOBase iobase, final Runnable task) {
            return null;
        }
    };
}
