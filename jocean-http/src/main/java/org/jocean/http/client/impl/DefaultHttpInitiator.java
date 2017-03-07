/**
 * 
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.InboundEndpointSupport;
import org.jocean.http.util.OutboundEndpointSupport;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
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
class DefaultHttpInitiator extends DefaultAttributeMap 
    implements HttpInitiator, Comparable<DefaultHttpInitiator>, Transformer<Object, Object>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
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
                .append(", inbound subscriber count=").append(_inboundSupport.subscribersCount())
                .append(", isInboundReceived=").append(_isInboundReceived.get())
                .append(", requestMethod=").append(_requestMethod)
                .append(", requestUri=").append(_requestUri)
                .append(", isInboundCompleted=").append(_isInboundCompleted.get())
                .append(", isOutboundSetted=").append(_isOutboundSetted.get())
                .append(", isOutboundSended=").append(_isOutboundSended.get())
                .append(", isOutboundCompleted=").append(_isOutboundCompleted.get())
                .append(", isKeepAlive=").append(_isKeepAlive.get())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInitiator.class);
    
    private final InterfaceSelector _selector = new InterfaceSelector();
    
    @SafeVarargs
    DefaultHttpInitiator(
        final Channel channel, 
        final Action1<HttpInitiator> ... onTerminates) {
        // TODO if channel.isActive() == false ?
        
        this._terminateAwareSupport = 
            new TerminateAwareSupport<HttpInitiator>(_selector);
        this._channel = channel;
        
        for (Action1<HttpInitiator> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        
        this._trafficCounter = RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.TRAFFICCOUNTER);
        
        //  TODO, test for channel already inactive
        RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        fireClosed();
                    }});
        
        this._inboundSupport = 
            new InboundEndpointSupport(
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
    }

    public void setApplyToRequest(final ApplyToRequest applyToRequest) {
        this._applyToRequest = applyToRequest;
    }

    @Override
    public Observable<Object> call(final Observable<Object> outboundMessage) {
        return outboundMessage.doOnNext(new Action1<Object>() {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest) {
                    final HttpRequest req = (HttpRequest)msg;
                    
                    final ApplyToRequest applyTo = _applyToRequest;
                    if (null != applyTo) {
                        try {
                            applyTo.call(req);
                        } catch (Exception e) {
                            LOG.warn("exception when invoke applyToRequest.call, detail: {}",
                              ExceptionUtils.exception2detail(e));
                        }
                    }
                    
                    _requestMethod = req.method().name();
                    _requestUri = req.uri();
                    _isKeepAlive.set(HttpUtil.isKeepAlive(req));
                }
                _isOutboundSended.compareAndSet(false, true);
            }})
            .doOnCompleted(new Action0() {
                @Override
                public void call() {
                    _isOutboundCompleted.compareAndSet(false, true);
                    _inboundSupport.readMessage();
                }})
            .doOnError(new Action1<Throwable>() {
                @Override
                public void call(Throwable e) {
                    LOG.warn("initiator({})'s outbound.onError, invoke fireClosed() and detail:{}",
                            DefaultHttpInitiator.this, ExceptionUtils.exception2detail(e));
                    fireClosed();
                }});
    }
    
    private Transformer<HttpObject, HttpObject> markInboundStateAndCloseOnError() {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> inbound) {
                return inbound.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        _isInboundReceived.compareAndSet(false, true);
                    }})
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        _isInboundCompleted.set(true);
                    }})
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        LOG.warn("initiator({})'s inbound.onError, invoke fireClosed() and detail:{}",
                                DefaultHttpInitiator.this, ExceptionUtils.exception2detail(e));
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
        return (this._isInboundCompleted.get() 
            && this._isOutboundCompleted.get()
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
    public Action1<Action1<HttpInitiator>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf(this);
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
                
    @Override
    public Action0 doOnTerminate(final Action1<HttpInitiator> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(this, onTerminate);
    }
    
    private static final ActionN FIRE_CLOSED = new ActionN() {
        @Override
        public void call(final Object... args) {
            ((DefaultHttpInitiator)args[0]).fireClosed0();
        }};
        
    private void fireClosed() {
        this._selector.destroyAndSubmit(FIRE_CLOSED, this);
    }

    private void fireClosed0() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active initiator[channel: {}] with isOutboundCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isOutboundCompleted.get(), this.isEndedWithKeepAlive());
        }
        //  fire all pending subscribers onError with unactived exception
        this._inboundSupport.fireAllSubscriberUnactive();
        this._terminateAwareSupport.fireAllTerminates(this);
    }

    private final TerminateAwareSupport<HttpInitiator> _terminateAwareSupport;
    private final InboundEndpointSupport _inboundSupport;
    private final OutboundEndpointSupport _outboundSupport;
    private volatile ApplyToRequest _applyToRequest;
    
    private final Channel _channel;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isInboundReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isInboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSended = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
}
