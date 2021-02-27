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
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action2;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends HttpTradeConnection<HttpTrade> implements HttpTrade, Comparable<DefaultHttpTrade> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpTrade.class);

    @Override
    public long startTimeMillis() {
        return this._createTimeMillis;
    }

    @Override
    public Completable inboundCompleted() {
        return received().toCompletable();
    }

    @Override
    public Observable<FullMessage<HttpRequest>> inbound() {
        return this._inboundRef.get();
    }

    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return this.doSetOutbound(message);
    }

    boolean isKeepAlive() {
        return this._isKeepAlive;
    }

    DefaultHttpTrade(final Channel channel) {
        super(channel);

        if (!channel.eventLoop().inEventLoop()) {
            throw new RuntimeException("Can't create trade out of channel(" + channel +")'s eventLoop.");
        }

        _inboundRef.set(Observable.error(new RuntimeException("not ready")));

        received().subscribe(any -> {}, e -> {}, () -> endofRecving());

        received().first().subscribe(slice -> {
            startRecving();
            final Iterator<? extends DisposableWrapper<? extends HttpObject>> iter = slice.element().iterator();
            if (iter.hasNext()) {
                final HttpObject hobj = iter.next().unwrap();
                if (hobj instanceof HttpRequest) {
                    // TODO, wrap request as pure http request while income FullHttpRequest
                    // or when sending
                    final HttpRequest req = (HttpRequest)hobj;
                    onHttpRequest(req);
                    _inboundRef.set(Observable.just(fullRequest(req, slice)));
                }
            }
        });

        // set in transacting flag
        writeCtrl().sending().first().subscribe( any -> startSending());

        writeCtrl().sended().subscribe(any -> {}, e -> {}, () -> {
                endofTransaction();
                // close normally
                close();
            });
    }

    public static Func1<DefaultHttpTrade, Observable<DefaultHttpTrade>> toInboundCompleted() {
        return dht -> dht.inbound().flatMap(fhr -> fhr.body().flatMap(body -> {
            final Observable<? extends ByteBufSlice> cachedContent =
                    body.content().doOnNext(bbs -> bbs.step()).cache();
            return cachedContent.last().<MessageBody>map(any -> new MessageBody() {
                @Override
                public HttpHeaders headers() {
                    return body.headers();
                }                            @Override
                public String contentType() {
                    return body.contentType();
                }
                @Override
                public int contentLength() {
                    return body.contentLength();
                }
                @Override
                public Observable<? extends ByteBufSlice> content() {
                    return cachedContent;
                }});
        }).map(body -> {
             dht._inboundRef.set(Observable.<FullMessage<HttpRequest>>just(new FullMessage<HttpRequest>() {
                 @Override
                 public String toString() {
                     return fhr.toString();
                 }
                @Override
                public HttpRequest message() {
                    return fhr.message();
                }

                @Override
                public Observable<? extends MessageBody> body() {
                    return Observable.just(body);
                }}));
            return dht;
        }));
    }

    private FullMessage<HttpRequest> fullRequest(
            final HttpRequest req,
            final HttpSlice sliceWithReq) {
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
                        return Observable.merge(received(), Observable.just(sliceWithReq))
                            .doOnNext(slice -> LOG.debug("content onNext slice: {}", slice))
                            .map(HttpSliceUtil.hs2bbs())
                            .doOnNext(bbs -> LOG.debug("content onNext bbs: {}", bbs))
                            ;
                    }});
            }};
    }

    @Override
    protected void onChannelInactive() {
        if (inTransacting()) {
            // TODO
            fireClosed(new TransportException("channelInactive of " + this._channelRef.get()));
        } else {
            LOG.debug("channel inactive after transaction finished, MAYBE Connection: close");
            // close normally
            close();
        }
    }

    private void onHttpRequest(final HttpRequest req) {
        this._requestMethod = req.method().name();
        this._requestUri = req.uri();
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
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

    private final AtomicReference<Observable<FullMessage<HttpRequest>>> _inboundRef = new AtomicReference<>();

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
                .append(", channel=").append(_channelRef.get())
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

    @Override
    public int inboundContentSize() {
        return this._contentSize.get();
    }

    @Override
    public String inboundTracing() {
        return this._readTracing.toString();
    }
}
