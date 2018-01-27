/**
 * 
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.jocean.http.IOBase;
import org.jocean.http.TransportException;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.DisposableWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpInitiator extends IOBase<HttpInitiator>
    implements HttpInitiator, Comparable<DefaultHttpInitiator>{
    
    private static final Action1<Object> _ADD_ACCEPT_ENCODING = new Action1<Object>() {
        @Override
        public void call(final Object msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest request = (HttpRequest)msg;
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
        DefaultHttpInitiator other = (DefaultHttpInitiator) obj;
        if (this._id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpInitiator [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", isActive=").append(isActive())
                .append(", transactionStatus=").append(transactionStatusAsString())
                .append(", isKeepAlive=").append(isKeepAlive())
                .append(", isRequestCompleted=").append(_isRequestCompleted)
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInitiator.class);
    
    @Override
    protected boolean needRead() {
        return inTransacting();
    }
    
    Channel channel() {
        return this._channel;
    }
    
    boolean isKeepAlive() {
        return this._isKeepAlive;
    }
    
    DefaultHttpInitiator(final Channel channel) {
        super(channel);
        
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
        }
    }

    private void subscribeResponse(
            final Observable<? extends Object> obsRequest,
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        if (subscriber.isUnsubscribed()) {
            LOG.info("response subscriber ({}) has been unsubscribed, ignore", 
                    subscriber);
            return;
        }
        if (holdInboundAndInstallHandler(subscriber)) {
            setOutbound(wrapRequest(obsRequest));
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _op.doOnUnsubscribeResponse(DefaultHttpInitiator.this, subscriber);
                }}));
        } else {
            // _respSubscriber field has already setted
            subscriber.onError(new RuntimeException("Transaction in progress"));
        }
    }

    @Override
    public Observable<? extends DisposableWrapper<HttpObject>> defineInteraction(final Observable<? extends Object> request) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<DisposableWrapper<HttpObject>>() {
            @Override
            public void call(final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
                _op.subscribeResponse(DefaultHttpInitiator.this, request, subscriber);
            }});
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

    private Observable<? extends Object> wrapRequest(
            final Observable<? extends Object> request) {
        if (Nettys.isSupportCompress(this._channel)) {
            return request.doOnNext(_ADD_ACCEPT_ENCODING);
        } else {
            return request;
        }
    }

    private void onHttpRequest(final HttpRequest req) {
        this._isKeepAlive = HttpUtil.isKeepAlive(req);
    }

    @Override
    protected void inboundOnNext(final HttpObject inmsg) {
        markStartRecving();
    }

    @Override
    protected void inboundOnCompleted() {
        endTransaction();
    }
    
    @Override
    protected void outboundOnNext(final Object outmsg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending http request msg {}", outmsg);
        }
        // set in transacting flag
        markStartSending();
        
        if (outmsg instanceof HttpRequest) {
            onHttpRequest((HttpRequest)outmsg);
        }
        
        sendOutmsg(outmsg);
    }

    @Override
    protected void outboundOnCompleted() {
        // force flush for _isFlushPerWrite = false
        this._channel.flush();
        this._isRequestCompleted = true;
        this.readMessage();
    }

    private void doOnUnsubscribeResponse(
            final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
        if (unholdInboundAndUninstallHandler(subscriber)) {
            // unsubscribe before OnCompleted or OnError
            fireClosed(new RuntimeException("unsubscribe response"));
        }
    }

    private void markStartSending() {
        transactionUpdater.compareAndSet(this, STATUS_IDLE, STATUS_SEND);
    }
    
    private void markStartRecving() {
        transactionUpdater.compareAndSet(this, STATUS_SEND, STATUS_RECV);
    }
    
    private void endTransaction() {
        transactionUpdater.compareAndSet(this, STATUS_RECV, STATUS_IDLE);
    }
    
    private int transactionStatus() {
        return transactionUpdater.get(this);
    }

    boolean inTransacting() {
        return transactionStatus() > STATUS_IDLE;
    }
    
    private static final AtomicIntegerFieldUpdater<DefaultHttpInitiator> transactionUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultHttpInitiator.class, "_transactionStatus");
    
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_SEND = 1;
    private static final int STATUS_RECV = 2;
    
    @SuppressWarnings("unused")
    private volatile int _transactionStatus = STATUS_IDLE;
    
    private volatile boolean _isKeepAlive = true;
    
    private volatile boolean _isRequestCompleted = false;
    
    private final long _createTimeMillis = System.currentTimeMillis();

    private final Op _op;

    protected interface Op {
        public void subscribeResponse(
                final DefaultHttpInitiator initiator,
                final Observable<? extends Object> request,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber);
        
        public void doOnUnsubscribeResponse(
                final DefaultHttpInitiator initiator,
                final Subscriber<? super DisposableWrapper<HttpObject>> subscriber);
    }
    
    private static final Op OP_ACTIVE = new Op() {
      @Override
      public void subscribeResponse(
              final DefaultHttpInitiator initiator,
              final Observable<? extends Object> request,
              final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
          initiator.subscribeResponse(request, subscriber);
      }
      
      @Override
      public void doOnUnsubscribeResponse(
              final DefaultHttpInitiator initiator,
              final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
          initiator.doOnUnsubscribeResponse(subscriber);
      }
  };
  
  private static final Op OP_UNACTIVE = new Op() {
      @Override
      public void subscribeResponse(
              final DefaultHttpInitiator initiator,
              final Observable<? extends Object> request,
              final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
          subscriber.onError(new RuntimeException("http connection unactive."));
      }
      
      @Override
      public void doOnUnsubscribeResponse(
              final DefaultHttpInitiator initiator,
              final Subscriber<? super DisposableWrapper<HttpObject>> subscriber) {
      }
  };
}
