/**
 * 
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.HttpHandlers;
import org.jocean.http.util.InboundEndpointSupport;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.OutboundEndpointSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.DefaultAttributeMap;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends DefaultAttributeMap 
    implements HttpTrade,  Comparable<DefaultHttpTrade>, Transformer<Object, Object>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
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
                .append(", request subscriber count=").append(_inboundSupport.subscribersCount())
                .append(", isRequestReceived=").append(_isRequestReceived.get())
                .append(", requestMethod=").append(_requestMethod)
                .append(", requestUri=").append(_requestUri)
                .append(", isRequestCompleted=").append(_isRequestCompleted.get())
                .append(", isResponseSetted=").append(_isResponseSetted.get())
                .append(", isResponseSended=").append(_isResponseSended.get())
                .append(", isResponseCompleted=").append(_isResponseCompleted.get())
                .append(", isKeepAlive=").append(_isKeepAlive.get())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final Action1<HttpTrade> ... onTerminates) {
        
        this._channel = channel;
        this._terminateAwareSupport = 
                new TerminateAwareSupport<HttpTrade>(_selector);
        
        //  在 HTTPOBJ_SUBSCRIBER 添加到 channel.pipeline 后, 再添加 channelInactive 的处理 Handler
        this._trafficCounter = Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.TRAFFICCOUNTER);
        Nettys.applyToChannel(onTerminate(), 
                channel, 
                HttpHandlers.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        fireClosed();
                    }});
        
        this._inboundSupport = 
            new InboundEndpointSupport(
                _createTimeMillis,
                _selector,
                channel,
                markInboundStateAndCloseOnError(),
                _trafficCounter,
                onTerminate());
        
        this._outboundSupport = 
            new OutboundEndpointSupport(
                _selector,
                channel,
                this,
                _trafficCounter,
                onTerminate());
        
        for (Action1<HttpTrade> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        if (!channel.isActive()) {
            fireClosed();
        }
    }

    @Override
    public Observable<Object> call(final Observable<Object> outboundMessage) {
        return outboundMessage.doOnNext(new Action1<Object>() {
            @Override
            public void call(final Object msg) {
                _isResponseSended.compareAndSet(false, true);
            }})
            .doOnCompleted(new Action0() {
                @Override
                public void call() {
                    _isResponseCompleted.compareAndSet(false, true);
                    _channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                    .addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future)
                                throws Exception {
                            //  TODO, flush success or failed
                            try {
                                fireClosed();
                            } catch (Exception e) {
                                LOG.warn("exception when ({}).doClose, detail:{}",
                                        this, ExceptionUtils.exception2detail(e));
                            }
                        }});
                }})
            .doOnError(new Action1<Throwable>() {
                @Override
                public void call(Throwable e) {
                    LOG.warn("trade({})'s outbound.onError, invoke fireClosed() and detail:{}",
                            DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                    fireClosed();
                }});
    }
    
    private Transformer<HttpObject, HttpObject> markInboundStateAndCloseOnError() {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> src) {
                return src.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        if (msg instanceof HttpRequest) {
                            _requestMethod = ((HttpRequest)msg).method().name();
                            _requestUri = ((HttpRequest)msg).uri();
                            _isRequestReceived.compareAndSet(false, true);
                            _isKeepAlive.set(HttpUtil.isKeepAlive((HttpRequest)msg));
                        } else if (!_isRequestReceived.get()) {
                            LOG.warn("trade {} missing http request and recv httpobj {}", 
                                DefaultHttpTrade.this, msg);
                        }
                    }})
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        _isRequestCompleted.set(true);
                    }})
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        LOG.warn("trade({})'s inbound.onError, invoke fireClosed() and detail:{}",
                                DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                        fireClosed();
                    }});
            }};
    }
    
    @Override
    public TrafficCounter trafficCounter() {
        return this._trafficCounter;
    }
    
    @Override
    public boolean isActive() {
        return this._selector.isActive();
    }

    public boolean isEndedWithKeepAlive() {
        return (this._isRequestCompleted.get() 
            && this._isResponseCompleted.get()
            && this._isKeepAlive.get());
    }

    @Override
    public void close() {
        fireClosed();
    }

    @Override
    public Object transport() {
        return this._channel;
    }
    
    @Override
    public OutboundEndpoint outbound() {
        return this._outboundSupport;
    }
    
    @Override
    public InboundEndpoint inbound() {
        return this._inboundSupport;
    }

    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate(this);
    }

    @Override
    public Action1<Action1<HttpTrade>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }

    @Override
    public Action0 doOnTerminate(Action1<HttpTrade> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultHttpTrade)args[0]).fireClosed0();
        }};
        
    private void fireClosed() {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this);
    }

    private void fireClosed0() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isResponseCompleted.get(), this.isEndedWithKeepAlive());
        }
        //  TODO add reason
        this._inboundSupport.fireAllSubscriberUnactive(null);
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private final TerminateAwareSupport<HttpTrade> _terminateAwareSupport;
    private final InboundEndpointSupport _inboundSupport;
    private final OutboundEndpointSupport _outboundSupport;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
}
