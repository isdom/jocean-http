package org.jocean.http.server.impl;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import rx.Observer;
import rx.functions.Action1;

final class TradeTransport {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(TradeTransport.class);
    
    private final Channel _channel;
    private Action1<Boolean> _recycleChannelAction;
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final AtomicBoolean _isClosed = new AtomicBoolean(false);

    TradeTransport(final Channel channel) {
        this._channel = channel;
    }
    
    void setRecycleChannelAction(final Action1<Boolean> recycleChannelAction) {
        this._recycleChannelAction = recycleChannelAction;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("TradeTransport [isRequestCompleted=")
                .append(_isRequestCompleted.get()).append(", isKeepAlive=")
                .append(_isKeepAlive.get()).append(", isClosed=")
                .append(_isClosed.get()).append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private final Observer<HttpObject> _requestObserver = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("TradeTransport: request onCompleted, channel: ({})", _channel);
            }
            _isRequestCompleted.set(true);
        }
    
        @Override
        public void onError(final Throwable e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("TradeTransport: request onError, channel: ({}), detail: {}", 
                        _channel,
                        ExceptionUtils.exception2detail(e));
            }
            onTradeClosed(false);
        }
    
        @Override
        public void onNext(final HttpObject httpobj) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("TradeTransport: request onNext: httpobj ({})", httpobj);
            }
            if (httpobj instanceof HttpRequest) {
                _isKeepAlive.set(HttpHeaders.isKeepAlive((HttpRequest)httpobj));
            }
        }
    };
    
    Observer<HttpObject> requestObserver() {
        return this._requestObserver;
    }
    
    private final Observer<HttpObject> _responseObserver = new Observer<HttpObject>() {
        @Override
        public void onCompleted() {
            try {
                onTradeClosed(true);
            } catch (Exception e) {
                LOG.warn("exception when ({}).onTradeClosed, detail:{}",
                        TradeTransport.this, ExceptionUtils.exception2detail(e));
            }
        }

        @Override
        public void onError(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, detail:{}",
                    TradeTransport.this, ExceptionUtils.exception2detail(e));
            try {
                onTradeClosed(false);
            } catch (Exception e1) {
                LOG.warn("exception when ({}).onTradeClosed, detail:{}",
                        TradeTransport.this, ExceptionUtils.exception2detail(e1));
            }
        }

        @Override
        public void onNext(final HttpObject msg) {
            if (isActive()) {
                _channel.write(ReferenceCountUtil.retain(msg));
            } else {
                LOG.warn("sendback msg({}) on closed transport[channel: {}]",
                    msg, _channel);
            }
        }
    };
    
    Observer<HttpObject> responseObserver() {
        return this._responseObserver;
    }
    
    private boolean isActive() {
        return !this._isClosed.get();
    }

    private boolean checkActiveAndTryClose() {
        return this._isClosed.compareAndSet(false, true);
    }
    
    private synchronized void onTradeClosed(final boolean isResponseCompleted) {
        if (checkActiveAndTryClose()) {
            final boolean canReuseChannel = 
                    this._isRequestCompleted.get() 
                    && isResponseCompleted 
                    && this._isKeepAlive.get();
            this._recycleChannelAction.call(canReuseChannel);
            if (LOG.isDebugEnabled()) {
                LOG.debug("invoke onTradeClosed on active transport[channel: {}] with canReuseChannel({})", 
                        this._channel, canReuseChannel);
            }
        } else {
            LOG.warn("invoke onTradeClosed on closed transport[channel: {}]", this._channel);
        }
    }

}