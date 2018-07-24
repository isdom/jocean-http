/**
 *
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.HttpConnection;
import org.jocean.http.HttpSlice;
import org.jocean.http.HttpSliceUtil;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.rx.RxObservables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpInitiator extends HttpConnection<HttpInitiator>
    implements HttpInitiator, Comparable<DefaultHttpInitiator> {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInitiator.class);

    private Observable<FullMessage<HttpResponse>> doInteraction(final Observable<? extends Object> request) {
        final Observable<? extends HttpSlice> rawInbound = rawInbound().doOnSubscribe(new Action0() {
            @Override
            public void call() {
                readMessage();
                setOutbound(wrapRequest(request));
            }
        }).compose(RxObservables.<HttpSlice>ensureSubscribeAtmostOnce()).share();

        return rawInbound.flatMap(new Func1<HttpSlice, Observable<FullMessage<HttpResponse>>>() {
            @Override
            public Observable<FullMessage<HttpResponse>> call(final HttpSlice slice) {
                return slice.element().first().map(DisposableWrapperUtil.<HttpObject>unwrap()).flatMap(new Func1<HttpObject, Observable<FullMessage<HttpResponse>>>() {
                    @Override
                    public Observable<FullMessage<HttpResponse>> call(final HttpObject hobj) {
                        if (hobj instanceof HttpResponse) {
                            final HttpResponse resp = (HttpResponse)hobj;
                            return Observable.<FullMessage<HttpResponse>>just(new FullMessage<HttpResponse>() {
                                @Override
                                public HttpResponse message() {
                                    return resp;
                                }

                                @Override
                                public Observable<? extends MessageBody> body() {
                                    return Observable.just(new MessageBody() {
                                        @Override
                                        public String contentType() {
                                            return resp.headers().get(HttpHeaderNames.CONTENT_TYPE);
                                        }

                                        @Override
                                        public int contentLength() {
                                            return HttpUtil.getContentLength(resp, -1);
                                        }

                                        @Override
                                        public Observable<? extends ByteBufSlice> content() {
                                            return Observable.just(slice).concatWith(rawInbound).doOnNext(new Action1<HttpSlice>() {
                                                @Override
                                                public void call(final HttpSlice slice) {
                                                    LOG.debug("{}'s content onNext: {}", resp, slice);
                                                }
                                            }).takeUntil(new Func1<HttpSlice, Boolean>() {
                                                @Override
                                                public Boolean call(final HttpSlice slice) {
                                                    final HttpObject last = slice.element().last().toBlocking().single().unwrap();
                                                    LOG.debug("{}'s content onNext's last: {}", resp, last);
                                                    return last instanceof LastHttpContent;
                                                }
                                            }).doOnNext(new Action1<HttpSlice>() {
                                                @Override
                                                public void call(final HttpSlice hs) {
                                                    LOG.debug("{}'s content onNext's hs: {}", resp, hs);
                                                }
                                            }).map(HttpSliceUtil.hs2bbs()).doOnNext(new Action1<ByteBufSlice>() {
                                                @Override
                                                public void call(final ByteBufSlice bbs) {
                                                    LOG.debug("{}'s content onNext's bbs: {}", resp, bbs);
                                                }
                                            });
                                        }});
                                }});
                        } else {
                            return Observable.empty();
                        }
                    }});
                }});
    }

    @Override
    public Observable<FullMessage<HttpResponse>> defineInteraction(final Observable<? extends Object> request) {
        return Observable.defer(new Func0<Observable<FullMessage<HttpResponse>>>() {
            @Override
            public Observable<FullMessage<HttpResponse>> call() {
                return doInteraction(request);
            }});
    }

    Channel channel() {
        return this._channel;
    }

    boolean isKeepAlive() {
        return this._isKeepAlive;
    }

    DefaultHttpInitiator(final Channel channel) {
        super(channel);
    }

    private Observable<? extends Object> wrapRequest(final Observable<? extends Object> request) {
        if (Nettys.isSupportCompress(this._channel)) {
            return request.map(new Func1<Object, Object>() {
                @Override
                public Object call(final Object obj) {
                    if (obj instanceof HttpSlice) {
                        return HttpSliceUtil.transformElement((HttpSlice)obj, new Transformer<DisposableWrapper<? extends HttpObject>, DisposableWrapper<? extends HttpObject>>() {
                            @Override
                            public Observable<DisposableWrapper<? extends HttpObject>> call(
                                    final Observable<DisposableWrapper<? extends HttpObject>> element) {
                                return element.doOnNext(_ADD_ACCEPT_ENCODING);
                            }});
                    } else {
                        _ADD_ACCEPT_ENCODING.call(obj);
                        return obj;
                    }
                }});
        } else {
            return request;
        }
    }

    @Override
    protected void onChannelInactive() {
        if (inTransacting()) {
            fireClosed(new TransportException("channelInactive of " + this._channel));
        } else {
            LOG.debug("channel inactive after transaction finished, MAYBE Connection: close");
        }
    }

    @Override
    protected void onInboundMessage(final HttpObject inmsg) {
        startRecving();
    }

    @Override
    protected void onInboundCompleted() {
        endofTransaction();
    }

    @Override
    protected void beforeSendingOutbound(final Object outmsg) {
        LOG.debug("{} sending request msg({})", this, outmsg);

        // set in transacting flag
        startSending();

        if (outmsg instanceof HttpRequest) {
            this._isKeepAlive = HttpUtil.isKeepAlive((HttpRequest)outmsg);
        }
    }

    @Override
    protected void onOutboundCompleted() {
        // force flush for _isFlushPerWrite = false
        this._channel.flush();
        this._isRequestCompleted = true;
    }

    private void startSending() {
        transferStatus(STATUS_IDLE, STATUS_SEND);
    }

    private void startRecving() {
        transferStatus(STATUS_SEND, STATUS_RECV);
    }

    private void endofTransaction() {
        transferStatus(STATUS_RECV, STATUS_IDLE);
    }

    private String transactionStatusAsString() {
        switch(transactionStatus()) {
        case STATUS_IDLE:
            return "IDLE";
        case STATUS_SEND:
            return "SEND";
        case STATUS_RECV:
            return "RECV";
        default:
            return "UNKNOWN";
        }
    }

    private static final int STATUS_SEND = 1;
    private static final int STATUS_RECV = 2;

    private volatile boolean _isKeepAlive = true;

    private volatile boolean _isRequestCompleted = false;

    private final long _createTimeMillis = System.currentTimeMillis();

  private static final Action1<Object> _ADD_ACCEPT_ENCODING = new Action1<Object>() {
      @Override
      public void call(final Object msg) {
          if (DisposableWrapperUtil.unwrap(msg) instanceof HttpRequest) {
              final HttpRequest request = (HttpRequest)DisposableWrapperUtil.unwrap(msg);
              request.headers().add(
                  HttpHeaderNames.ACCEPT_ENCODING,
                  HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);
          }
      }};

  private static final AtomicInteger _IDSRC = new AtomicInteger(1);

  private final int _id = _IDSRC.getAndIncrement();

  @Override
  public int compareTo(final DefaultHttpInitiator o) {
      return this._id - o._id;
  }

  /* (non-Javadoc)
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + this._id;
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
      final DefaultHttpInitiator other = (DefaultHttpInitiator) obj;
      if (this._id != other._id)
          return false;
      return true;
  }

  @Override
  public String toString() {
      return new StringBuilder().append("DefaultHttpInitiator [create at:")
              .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
              .append(", onTerminateCnt=").append(this._terminateAwareSupport.onTerminateCount())
              .append(", isActive=").append(isActive())
              .append(", transactionStatus=").append(transactionStatusAsString())
              .append(", isKeepAlive=").append(isKeepAlive())
              .append(", isRequestCompleted=").append(_isRequestCompleted)
              .append(", channel=").append(_channel)
              .append("]")
              .toString();
  }

}
