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
import org.jocean.http.util.APPLY;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.InboundEndpointSupport;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpInitiator extends DefaultAttributeMap 
    implements HttpInitiator, Comparable<DefaultHttpInitiator>  {
    
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
    
    private final FuncSelector<DefaultHttpInitiator> _funcSelector = 
            new FuncSelector<>(this);
    
    @SafeVarargs
    DefaultHttpInitiator(
        final Channel channel, 
        final Action1<HttpInitiator> ... onTerminates) {
        this(channel, 0, onTerminates);
    }
    
    @SafeVarargs
    DefaultHttpInitiator(
        final Channel channel, 
        final int inboundBlockSize,
        final Action1<HttpInitiator> ... onTerminates) {
        // TODO if channel.isActive() == false ?
        
        this._terminateAwareSupport = 
            new TerminateAwareSupport<HttpInitiator, DefaultHttpInitiator>(
                this, _funcSelector);
        this._channel = channel;
        
        final HttpMessageHolder holder = new HttpMessageHolder(inboundBlockSize);
        
        doOnTerminate(holder.release());
        final Observable<? extends HttpObject> cachedInbound = 
                RxNettys.inboundFromChannel(channel, onTerminate())
                .compose(holder.<HttpObject>assembleAndHold())
                .compose(markInboundStateAndCloseOnError())
                .cache()
                .compose(RxNettys.duplicateHttpContent())
                ;
        
        cachedInbound.subscribe(
            RxSubscribers.ignoreNext(),
            new Action1<Throwable>() {
                @Override
                public void call(final Throwable e) {
                    LOG.warn("initiator({})'s internal request subscriber invoke with onError {}", 
                            DefaultHttpInitiator.this, ExceptionUtils.exception2detail(e));
                }});
        
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
            new InboundEndpointSupport<DefaultHttpInitiator>(
                _funcSelector,
                channel,
                cachedInbound,
                holder,
                _trafficCounter,
                onTerminate());
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
        return this._funcSelector.isActive();
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
        return new OutboundEndpoint() {
            public void setFlushPerWrite(final boolean isFlushPerWrite) {
                _isFlushPerWrite = isFlushPerWrite;
            }
            
            @Override
            public Action0 doOnWritabilityChanged(final Action1<OutboundEndpoint> onWritabilityChanged) {
                _doAddWritabilityChanged.call(onWritabilityChanged);
                return new Action0() {
                    @Override
                    public void call() {
                        _funcSelector.submitWhenActive(REMOVE_WRITABILITYCHANGED).call(onWritabilityChanged);
                    }};
            }
            
            @Override
            public Action0 doOnSended(final Action1<Object> onSended) {
                return _doAddOnSended.call(onSended);
            }
            
            @Override
            public long outboundBytes() {
                return _trafficCounter.outboundBytes();
            }

            @Override
            public Subscription message(final Observable<? extends Object> message) {
                return _doSetOutboundMessage.call(message);
            }};
    }
    
    private volatile boolean _isFlushPerWrite = false;
    
    private final COWCompositeSupport<Action1<Object>> _onSendeds = 
            new COWCompositeSupport<>();
        
    private final Func1<Action1<Object>, Action0> _doAddOnSended = 
        RxFunctions.toFunc1(
            _funcSelector.callWhenActive(
                new Func1_N<DefaultHttpInitiator, Action0>() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public Action0 call(final DefaultHttpInitiator t,
                            final Object... args) {
                        final Action1<Object> onSended = (Action1<Object>)args[0];
                        t._onSendeds.addComponent(onSended);
                        return new Action0() {
                            @Override
                            public void call() {
                                t._onSendeds.removeComponent(onSended);
                            }};
                    }})
        );
    
    private final Action1_N<DefaultHttpInitiator> REMOVE_WRITABILITYCHANGED = 
            new Action1_N<DefaultHttpInitiator>() {
                @SuppressWarnings("unchecked")
                @Override
                public void call(final DefaultHttpInitiator t,
                        final Object... args) {
                    _onWritabilityChangeds.removeComponent((Action1<OutboundEndpoint>)args[0]);
                }};
                
    private final COWCompositeSupport<Action1<OutboundEndpoint>> _onWritabilityChangeds = 
            new COWCompositeSupport<>();
        
    private final Action1<Action1<OutboundEndpoint>> _doAddWritabilityChanged = 
        RxActions.toAction1(
            _funcSelector.submitWhenActive(
                new Action1_N<DefaultHttpInitiator>() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public void call(final DefaultHttpInitiator t,
                            final Object... args) {
                        t._onWritabilityChangeds.addComponent((Action1<OutboundEndpoint>)args[0]);
                    }})
        );
    
    private final Func1<Observable<? extends Object>, Subscription> 
        _doSetOutboundMessage = 
            RxFunctions.toFunc1(
            this._funcSelector.callWhenActive(
                RxFunctions.<DefaultHttpInitiator,Subscription>toFunc1_N(
                    DefaultHttpInitiator.class, "setOutboundMessage0"))
            .callWhenDestroyed(Func1_N.Util.<DefaultHttpInitiator,Subscription>returnNull()));

    @SuppressWarnings("unused")
    private Subscription setOutboundMessage0(
        final Observable<? extends Object> message) {
        if (this._isOutboundSetted.compareAndSet(false, true)) {
            return message.subscribe(
                    outboundOnNext(),
                    doOutboundOnError(),
                    outboundOnCompleted());
        } else {
            LOG.warn("initiator({}) 's outbound message has setted, ignore this message({})",
                    this, message);
            return null;
        }
    }
    
    private final Action1<Object> outboundOnNext() {
        return RxActions.<Object>toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "outboundOnNext0")));
    }
    
    private static final Action1_N<Action1<Object>> _callOnSended = new Action1_N<Action1<Object>>() {
        @Override
        public void call(final Action1<Object> onSended,final Object... args) {
            onSended.call(args[0]);
        }};
        
    @SuppressWarnings("unused")
    private void outboundOnNext0(final Object msg) {
        if (msg instanceof HttpRequest) {
            final HttpRequest req = (HttpRequest)msg;
            
            _requestMethod = req.method().name();
            _requestUri = req.uri();
            _isKeepAlive.set(HttpUtil.isKeepAlive(req));
        }
        this._isOutboundSended.compareAndSet(false, true);
        
        final ChannelFuture future = this._isFlushPerWrite
            ? this._channel.writeAndFlush(ReferenceCountUtil.retain(msg))
            : this._channel.write(ReferenceCountUtil.retain(msg))
            ;
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                _onSendeds.foreachComponent(_callOnSended, msg);
            }});
    }

    private final Action1<Throwable> doOutboundOnError() {
        return RxActions.<Throwable>toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "outboundOnError0")));
    }
    
    @SuppressWarnings("unused")
    private void outboundOnError0(final Throwable e) {
        LOG.warn("initiator({})'s outbound.onError, invoke doAbort() and detail:{}",
            DefaultHttpInitiator.this, ExceptionUtils.exception2detail(e));
        doAbort();
    }

    private final Action0 outboundOnCompleted() {
        return RxActions.toAction0(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "outboundOnCompleted0")));
    }

    @SuppressWarnings("unused")
    private void outboundOnCompleted0() {
        this._isOutboundCompleted.compareAndSet(false, true);
        this._channel.flush();
    }

    @Override
    public InboundEndpoint inbound() {
        return this._inboundSupport;
    }
    
    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate();
    }
            
    @Override
    public Action1<Action1<HttpInitiator>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf();
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(onTerminate);
    }
                
    @Override
    public Action0 doOnTerminate(final Action1<HttpInitiator> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(onTerminate);
    }
    
    private void doAbort() {
        this._funcSelector.destroy(
            RxActions.toAction1_N(DefaultHttpInitiator.class, "closeChannelAndFireClosed"));
    }
    
    @SuppressWarnings("unused")
    private void closeChannelAndFireClosed() {
        this._channel.close();
        fireClosed0();
    }
    
    private void fireClosed() {
        this._funcSelector.destroy(
            RxActions.toAction1_N(DefaultHttpInitiator.class, "fireClosed0"));
    }

    private void fireClosed0() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("close active initiator[channel: {}] with isOutboundCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isOutboundCompleted.get(), this.isEndedWithKeepAlive());
        }
        //  fire all pending subscribers onError with unactived exception
        this._inboundSupport.fireAllSubscriberUnactive();
        this._terminateAwareSupport.fireAllTerminates();
    }

    private final TerminateAwareSupport<HttpInitiator, DefaultHttpInitiator> 
        _terminateAwareSupport;
    
    private final InboundEndpointSupport<DefaultHttpInitiator> 
        _inboundSupport;
    
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
