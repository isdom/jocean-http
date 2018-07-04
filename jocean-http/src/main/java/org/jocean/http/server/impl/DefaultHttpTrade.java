/**
 *
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.HttpConnection;
import org.jocean.http.ReadComplete;
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
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends HttpConnection<HttpTrade>
    implements HttpTrade, Comparable<DefaultHttpTrade> {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);

    private static final Func1<Object, Object> _DUPLICATE_CONTENT = new Func1<Object, Object>() {
        @Override
        public Object call(final Object obj) {
            if ((obj instanceof DisposableWrapper)
                && (DisposableWrapperUtil.unwrap(obj) instanceof HttpContent)) {
                return DisposableWrapperUtil.<HttpObject>wrap(((HttpContent) DisposableWrapperUtil.unwrap(obj)).duplicate(),
                        (DisposableWrapper<?>)obj);
            } else {
                return obj;
            }
        }
    };

    private final CompositeSubscription _inboundCompleted = new CompositeSubscription();

    @Override
    public Completable inboundCompleted() {
        return Completable.create(new Completable.OnSubscribe() {
            @Override
            public void call(final CompletableSubscriber subscriber) {
                _inboundCompleted.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        subscriber.onCompleted();
                    }}));
            }});
    }

    @Override
    public Observable<? extends HttpRequest> request() {
        return this._cachedRequest;
    }

    @Override
    public Observable<? extends Object> inbound() {
        return request().flatMap(new Func1<HttpRequest, Observable<? extends Object>>() {
            @Override
            public Observable<? extends Object> call(final HttpRequest req) {
                return _inbound;
            }
        });
    }

    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return setOutbound(message);
    }

    boolean isKeepAlive() {
        return this._isKeepAlive;
    }

    DefaultHttpTrade(final Channel channel) {
        super(channel);

        if (!channel.eventLoop().inEventLoop()) {
            throw new RuntimeException("Can't create trade out of channel(" + channel +")'s eventLoop.");
        }

        /*
        this._inbound = rawInbound()
                .compose(new Transformer<Object, Object>() {
                    @Override
                    public Observable<Object> call(final Observable<Object> org) {
                        return org.doOnNext(new Action1<Object>() {
                            @Override
                            public void call(final Object obj) {
                                if (obj instanceof ReadComplete) {
                                    ((ReadComplete)obj).readInbound();
                                }
                            }}).filter(new Func1<Object, Boolean>() {
                                @Override
                                public Boolean call(final Object obj) {
                                    return !(obj instanceof ReadComplete);
                                }});
                    }})
                .cache().doOnNext(new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof DisposableWrapper && ((DisposableWrapper<?>)obj).isDisposed()) {
                    throw new RuntimeException("httpobject wrapper is disposed!");
                }
            }
        }).map(_DUPLICATE_CONTENT);

        this._inbound.subscribe(RxSubscribers.ignoreNext(), new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                LOG.warn("HttpTrade: {}'s inbound with onError {}", this, errorAsString(e));
            }
        });
        */

        final List<Object> inboundForepart = new ArrayList<>();

        final Observable<? extends Object> sharedRawInbound = rawInbound().share();

        this._cachedRequest = sharedRawInbound.flatMap(new Func1<Object, Observable<HttpRequest>>() {
            @Override
            public Observable<HttpRequest> call(final Object obj) {
                if (obj instanceof ReadComplete) {
                    if (inboundForepart.isEmpty()) {
                        // keep reading until get valid http request
                        LOG.info("readComplete without valid http request, continue read inbound");
                        ((ReadComplete) obj).readInbound();
                        return Observable.empty();
                    } else {
                        LOG.info("recv part request, and try to cache request");
                        inboundForepart.add(obj);
                        buildInbound(inboundForepart, sharedRawInbound);
                        return torequest(inboundForepart);
                    }
                } else {
                    inboundForepart.add(obj);
                    LOG.info("recv valid http request content {}, recv rest until readComplete.", obj);
                    return Observable.empty();
                }
            }
        }, new Func1<Throwable, Observable<HttpRequest>>() {
            @Override
            public Observable<HttpRequest> call(final Throwable e) {
                LOG.warn("recv request, and cache full request");
                return Observable.error(e);
            }
        }, new Func0<Observable<HttpRequest>>() {
            @Override
            public Observable<HttpRequest> call() {
                LOG.info("recv full request, and cache full request");
                buildInbound(inboundForepart, null);
                return torequest(inboundForepart);
            }
        }).first().cache();

        this._cachedRequest.subscribe(RxSubscribers.ignoreNext(), new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                LOG.warn("HttpTrade: {}'s cached request with onError {}", this, errorAsString(e));
            }
        });
    }

    private Observable<HttpRequest> torequest(final List<Object> inboundForepart) {
        return Observable.just((HttpRequest)DisposableWrapperUtil.unwrap(inboundForepart.get(0)) );
    }

    private void buildInbound(final List<Object> inboundForepart, final Observable<? extends Object> sharedRawInbound) {
        if (null != sharedRawInbound) {
            sharedRawInbound.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError(), new Action0() {
                @Override
                public void call() {
                    _inboundCompleted.unsubscribe();
                }});
            this._inbound = Observable.from(inboundForepart).concatWith(sharedRawInbound).map(_DUPLICATE_CONTENT);
        } else {
            this._inboundCompleted.unsubscribe();
            this._inbound = Observable.from(inboundForepart).map(_DUPLICATE_CONTENT);
        }
    }

    private Observable<? extends Object> rawInbound() {
        return Observable.unsafeCreate(new Observable.OnSubscribe<Object>() {
            @Override
            public void call(final Subscriber<? super Object> subscriber) {
                holdInboundAndInstallHandler(subscriber);
            }
        });
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

    private Observable<? extends Object> _inbound;

    private final Observable<? extends HttpRequest> _cachedRequest;

    private volatile boolean _isKeepAlive = false;

    private final long _createTimeMillis = System.currentTimeMillis();
    private String _requestMethod;
    private String _requestUri;

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
                .append(", onTerminateCnt=").append(this._terminateAwareSupport.onTerminateCount())
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
