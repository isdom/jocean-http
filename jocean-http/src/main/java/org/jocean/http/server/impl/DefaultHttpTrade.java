/**
 *
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ReadComplete;
import org.jocean.http.HttpConnection;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
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
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends HttpConnection<HttpTrade>
    implements HttpTrade, Comparable<DefaultHttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);

    @Override
    public void setAutoRead(final boolean autoRead) {
        this._autoRead = autoRead;
    }

    @Override
    public Observable<? extends DisposableWrapper<HttpObject>> inbound() {
        return this._obsRequest;
    }

    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return setOutbound(message);
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

        this._obsRequest = buildObsRequest(maxBufSize)
                .compose(new Transformer<DisposableWrapper<HttpObject>, DisposableWrapper<HttpObject>>() {
                    @Override
                    public Observable<DisposableWrapper<HttpObject>> call(final Observable<DisposableWrapper<HttpObject>> org) {
                        return org.doOnNext(new Action1<DisposableWrapper<HttpObject>>() {
                            @Override
                            public void call(final DisposableWrapper<HttpObject> hobj) {
                                if (_autoRead) {
                                    final Object o = DisposableWrapperUtil.unwrap(hobj);
                                    if ( o instanceof ReadComplete) {
                                        ((ReadComplete)o).readInbound();
                                    }
                                }
                            }}).filter(new Func1<DisposableWrapper<HttpObject>, Boolean>() {
                                @Override
                                public Boolean call(final DisposableWrapper<HttpObject> hobj) {
                                    return !(_autoRead && (DisposableWrapperUtil.unwrap(hobj) instanceof ReadComplete));
                                }});
                    }})
                .cache().doOnNext(new Action1<DisposableWrapper<HttpObject>>() {
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
                LOG.warn("HttpTrade: {}'s inbound with onError {}", this, errorAsString(e));
            }
        });
    }

    private Observable<? extends DisposableWrapper<HttpObject>> buildObsRequest(final int maxBufSize) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<DisposableWrapper<HttpObject>>() {
            @Override
            public void call(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
                holdInboundAndInstallHandler(subscriber);
            }
        })/*.compose(RxNettys.assembleTo(maxBufSize, DefaultHttpTrade.this))*/;
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

    @Override
    protected boolean needRead() {
        return inRecving();
    }

    @Override
    protected void onInboundMessage(final HttpObject inmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("HttpTrade: channel({}) invoke channelRead0 and call with msg({}).",
                this._channel, inmsg);
        }
        startRecving();
        if (inmsg instanceof HttpRequest) {
            onHttpRequest((HttpRequest)inmsg);
        }
    }

    private void onHttpRequest(final HttpRequest req) {
        this._requestMethod = req.method().name();
        this._requestUri = req.uri();
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
    }

    @Override
    protected void onInboundCompleted() {
        endofRecving();
    }

    @Override
    protected void beforeSendingOutbound(final Object outmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http response msg {}", outmsg);
        }
        // set in transacting flag
        startSending();
    }

    @Override
    protected void onOutboundCompleted() {
        // force flush for _isFlushPerWrite = false
        //  reference: https://github.com/netty/netty/commit/789e323b79d642ea2c0a024cb1c839654b7b8fad
        //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
        //  Detail:
        //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
        //
        //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        //
        // See https://github.com/netty/netty/issues/2983 for more information.
        this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(closeWhenComplete());
    }

    private ChannelFutureListener closeWhenComplete() {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                endofTransaction();
                if (future.isSuccess()) {
                    // close normally
                    close();
                } else {
                    fireClosed(new TransportException("flush response error", future.cause()));
                }
            }};
    }

    private void startRecving() {
        transferStatus(STATUS_IDLE, STATUS_RECV);
    }

    private void endofRecving() {
        transferStatus(STATUS_RECV, STATUS_RECV_END);
    }

    private void startSending() {
        transferStatus(STATUS_RECV_END, STATUS_SEND);
    }

    private void endofTransaction() {
        transferStatus(STATUS_SEND, STATUS_IDLE);
    }

    private boolean inRecving() {
        return transactionStatus() == STATUS_RECV;
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

    private static final int STATUS_RECV = 1;
    private static final int STATUS_RECV_END = 2;
    private static final int STATUS_SEND = 3;

    private final Observable<? extends DisposableWrapper<HttpObject>> _obsRequest;

    private volatile boolean _autoRead = true;

    private volatile boolean _isKeepAlive = false;

    private final long _createTimeMillis = System.currentTimeMillis();
    private String _requestMethod;
    private String _requestUri;

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
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final DefaultHttpTrade other = (DefaultHttpTrade) obj;
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
}
