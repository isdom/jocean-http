/**
 *
 */
package org.jocean.http.server.internal;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.HttpConnection;
import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Pair;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Completable;
import rx.CompletableSubscriber;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends HttpConnection<HttpTrade> implements HttpTrade, Comparable<DefaultHttpTrade> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpTrade.class);

    private final CompositeSubscription _inboundCompleted = new CompositeSubscription();

    @Override
    public long startTimeMillis() {
        return this._createTimeMillis;
    }

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
    public Observable<FullMessage<HttpRequest>> inbound() {
        return this._inbound;
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

        final Observable<? extends HttpSlice> rawInbound = rawInbound().share();

        this._inbound = rawInbound.flatMap(new Func1<HttpSlice, Observable<FullMessage<HttpRequest>>>() {
            @Override
            public Observable<FullMessage<HttpRequest>> call(final HttpSlice slice) {
                final Iterator<? extends DisposableWrapper<? extends HttpObject>> iter = slice.element().iterator();
                if (iter.hasNext()) {
                    final HttpObject hobj = iter.next().unwrap();
                    if (hobj instanceof HttpRequest) {
                        // TODO, wrap request as pure http request while income FullHttpRequest
                        // or when sending
                        final HttpRequest req = (HttpRequest)hobj;
//                        private static HttpRequest requestOf(final HttpRequest req) {
//                            return new ProxyBuilder<>(HttpRequest.class, req).buildProxy();
//                        }
                        return Observable.<FullMessage<HttpRequest>>just(fullRequest(req, slice, rawInbound));
                    }
                }
                return Observable.empty();
            }
        }).cache();

        this._inbound.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());
    }

    private FullMessage<HttpRequest> fullRequest(
            final HttpRequest req,
            final HttpSlice sliceWithReq,
            final Observable<? extends HttpSlice> rawInbound) {
        return new FullMessage<HttpRequest>() {
            @Override
            public String toString() {
                return new StringBuilder().append("FullMessage [req=").append(req).append("]").toString();
            }
            @Override
            public HttpRequest message() {
                return req;
            }

            @Override
            public Observable<? extends MessageBody> body() {
                return Observable.just(new MessageBody() {
                    @Override
                    public String toString() {
                        return new StringBuilder().append("MessageBody [req=").append(req)
                                .append(",body=(start with ").append(sliceWithReq).append(")]").toString();
                    }
                    @Override
                    public HttpHeaders headers() {
                        return req.headers();
                    }
                    @Override
                    public String contentType() {
                        return req.headers().get(HttpHeaderNames.CONTENT_TYPE);
                    }

                    @Override
                    public int contentLength() {
                        return HttpUtil.getContentLength(req, -1);
                    }

                    @Override
                    public Observable<? extends ByteBufSlice> content() {
                        return Observable.just(sliceWithReq).concatWith(rawInbound)
                        .doOnNext(new Action1<HttpSlice>() {
                            @Override
                            public void call(final HttpSlice slice) {
                                LOG.debug("{}'s content onNext: {}", req, slice);
                            }
                        })
                        .takeUntil(new Func1<HttpSlice, Boolean>() {
                            @Override
                            public Boolean call(final HttpSlice slice) {
                                final Iterator<? extends DisposableWrapper<? extends HttpObject>> iter = slice.element().iterator();
                                HttpObject last = null;
                                while (iter.hasNext()) {
                                    last = iter.next().unwrap();
                                }
                                return null != last && last instanceof LastHttpContent;
                            }
                        })
                        .doOnNext(new Action1<HttpSlice>() {
                            @Override
                            public void call(final HttpSlice hs) {
                                LOG.debug("{}'s content onNext's hs: {}", req, hs);
                            }
                        })
                        .map(HttpSliceUtil.hs2bbs())
                        .doOnNext(new Action1<ByteBufSlice>() {
                            @Override
                            public void call(final ByteBufSlice bbs) {
                                LOG.debug("{}'s content onNext's bbs: {}", req, bbs);
                            }
                        })
                        ;
                    }});
            }};
    }

    @Override
    protected void onChannelInactive() {
        if (inTransacting()) {
            fireClosed(new TransportException("channelInactive of " + this._channel));
        } else {
            LOG.debug("channel inactive after transaction finished, MAYBE Connection: close");
            // close normally
            close();
        }
    }

    @Override
    protected void onInboundMessage(final HttpObject inmsg) {
        LOG.debug("{} read msg({}).", this, inmsg);

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
        this._inboundCompleted.unsubscribe();
    }

    @Override
    protected void beforeSendingOutbound(final Object outmsg) {
        LOG.debug("sending response msg({}) for {}", outmsg, this);

        // set in transacting flag
        startSending();
    }

    @Override
    protected void onOutboundCompleted() {
        super.onOutboundCompleted();
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
                if (future.isSuccess()) {
                    endofTransaction();
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

    //  TODO:
    //  when 100 continue income
    //      we need first response by 100 ok
    //      then trade should continue receiving income
    //  https://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html#sec8.2.3
    private void endofRecving() {
        transferStatus(STATUS_RECV, STATUS_RECV_END);
    }

    private void startSending() {
        transferStatus(STATUS_RECV_END, STATUS_SEND);
    }

    private void endofTransaction() {
        transferStatus(STATUS_SEND, STATUS_IDLE);
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

    private final Observable<FullMessage<HttpRequest>> _inbound;

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
        return new StringBuilder()
                .append("DefaultHttpTrade [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", onEndCnt=").append(this._haltSupport.onHaltCount())
                .append(", requestMethod=").append(this._requestMethod)
                .append(", requestUri=").append(this._requestUri)
                .append(", isKeepAlive=").append(isKeepAlive())
                .append(", transactionStatus=").append(transactionStatusAsString())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]").toString();
    }

    @Override
    public void log(final Map<String, ?> fields) {
        _logs.add(Pair.of(System.currentTimeMillis() * 1000, fields));
    }

    @Override
    public void visitlogs(final Action2<Long, Map<String, ?>> logvisitor) {
        for (final Pair<Long, Map<String, ?>> log : _logs) {
            try {
                logvisitor.call(log.first, log.second);
            } catch (final Exception e)
            {}
        }
    }

    private final List<Pair<Long, Map<String, ?>>> _logs = new ArrayList<>();
}
