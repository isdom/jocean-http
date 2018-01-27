/**
 * 
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.jocean.http.IOBase;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends IOBase<HttpTrade> 
    implements HttpTrade, Comparable<DefaultHttpTrade> {
    
    private final Func1<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>> _DUPLICATE_CONTENT = new Func1<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>>() {
        @Override
        public DisposableWrapper<HttpObject> call(final DisposableWrapper<HttpObject> wrapper) {
            if (wrapper.unwrap() instanceof HttpContent) {
                return DisposableWrapperUtil.<HttpObject>wrap(((HttpContent) wrapper.unwrap()).duplicate(), wrapper);
            } else {
                return wrapper;
            }
        }
    };
        
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
                .append(", transactionStatus=").append(transactionStatusAsString())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    @Override
    protected boolean needRead() {
        return inRecving();
    }
    
    @Override
    public Observable<? extends DisposableWrapper<HttpObject>> inbound() {
        return this._obsRequest;
    }
    
    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return this._op.setOutbound(this, message);
    }

    boolean isKeepAlive() {
        return this._isKeepAlive;
    }
    
    DefaultHttpTrade(final Channel channel) {
        this(channel, 0);
    }
    
    DefaultHttpTrade(final Channel channel, final int maxBufSize) {
        super(channel);
        
        if (!channel.eventLoop().inEventLoop()) {
            throw new RuntimeException("Can't create trade out of channel(" + channel +")'s eventLoop.");
        }
        
        this._obsRequest = buildObsRequest(maxBufSize).cache().doOnNext(new Action1<DisposableWrapper<HttpObject>>() {
            @Override
            public void call(final DisposableWrapper<HttpObject> wrapper) {
                if (wrapper.isDisposed()) {
                    throw new RuntimeException("httpobject wrapper is disposed!");
                }
            }
        }).map(_DUPLICATE_CONTENT);

        this._obsRequest.subscribe(RxSubscribers.ignoreNext(), new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                LOG.warn("HttpTrade: {}'s inbound with onError {}", this, ExceptionUtils.exception2detail(e));
            }
        });
        
        this._op = this._selector.build(Op.class, OP_ACTIVE, OP_UNACTIVE);
    }

    @Override
    protected void onChannelInactive() {
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

    private Observable<? extends DisposableWrapper<HttpObject>> buildObsRequest(final int maxBufSize) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<DisposableWrapper<HttpObject>>() {
            @Override
            public void call(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
                holdInboundAndInstallHandler(subscriber);
            }
        }).compose(RxNettys.assembleTo(maxBufSize, DefaultHttpTrade.this));
    }

    private Subscription setOutbound(
            final Observable<? extends Object> outbound) {
        if (this._isOutboundSetted.compareAndSet(false, true)) {
            final Subscription subscription = outbound.subscribe(buildOutboundObserver());
            setOutboundSubscription(subscription);
            return subscription;
        } else {
            LOG.warn("trade({}) 's outbound message has setted, ignore this outbound({})",
                    this, outbound);
            return null;
        }
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
    
    private void onHttpRequest(final HttpRequest req) {
        this._requestMethod = req.method().name();
        this._requestUri = req.uri();
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
    }
    
    @Override
    protected void outboundOnNext(final Object outmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http response msg {}", outmsg);
        }
        // set in transacting flag
        markStartSending();
        
        sendOutmsg(outmsg);
    }
    
    @Override
    protected void outboundOnCompleted() {
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
    
    @Override
    protected void inboundOnNext(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber,
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
            // retain of create transfer to DisposableWrapper<HttpObject>
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
                markEndofRecving();
                removeInboundHandler();
                subscriber.onCompleted();
            }
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
    
    private final Observable<? extends DisposableWrapper<HttpObject>> _obsRequest;
    
    private volatile boolean _isKeepAlive = false;
    
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    
    private final long _createTimeMillis = System.currentTimeMillis();
    private String _requestMethod;
    private String _requestUri;
    
    private final Op _op;
    
    protected interface Op {
        public Subscription setOutbound(final DefaultHttpTrade trade, final Observable<? extends Object> outbound);
    }
    
    private static final Op OP_ACTIVE = new Op() {
        @Override
        public Subscription setOutbound(
                final DefaultHttpTrade trade,
                final Observable<? extends Object> outbound) {
            return trade.setOutbound(outbound);
        }
    };
    
    private static final Op OP_UNACTIVE = new Op() {
        @Override
        public Subscription setOutbound(final DefaultHttpTrade trade,
                final Observable<? extends Object> outbound) {
            return null;
        }
    };
}
