/**
 * 
 */
package org.jocean.http.client.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.TrafficCounterHandler;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

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
                .append(", inbound subscriber count=").append(_inboundSubscribers.size())
                .append(", isInboundReceived=").append(_isInboundReceived.get())
//                .append(", requestMethod=").append(_requestMethod)
//                .append(", requestUri=").append(_requestUri)
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
        final Action1<HttpInitiator> ... oncloseds) {
        this(channel, 0, oncloseds);
    }
    
    @SafeVarargs
    DefaultHttpInitiator(
        final Channel channel, 
        final int inboundBlockSize,
        final Action1<HttpInitiator> ... oncloseds) {
        this._channel = channel;
        this._trafficCounter = buildTrafficCounter(channel);
        this._inboundHolder = new HttpMessageHolder(inboundBlockSize);
        
        addCloseHook(RxActions.<HttpInitiator>toAction1(this._inboundHolder.release()));
        
        this._inboundObservable = RxNettys.inboundFromChannel(channel, doOnTerminate())
                .compose(this._inboundHolder.<HttpObject>assembleAndHold())
                .compose(hookRequest())
                .cache()
                .compose(RxNettys.duplicateHttpContent())
                ;
        for (Action1<HttpInitiator> hook : oncloseds) {
            addCloseHook(hook);
        }
        
        this._inboundObservable.subscribe(
                RxSubscribers.ignoreNext(),
                new Action1<Throwable>() {
                    @Override
                    public void call(final Throwable e) {
                        LOG.warn("initiator({})'s internal request subscriber invoke with onError {}", 
                                DefaultHttpInitiator.this, ExceptionUtils.exception2detail(e));
                    }});
        
        //  TODO, test for channel already inactive
        closeInitiatorWhenChannelInactive();
        hookChannelReadComplete();
    }

    public Action1<Action0> doOnTerminate() {
        return new Action1<Action0>() {
            @Override
            public void call(final Action0 action) {
                addCloseHook(RxActions.<HttpInitiator>toAction1(action));
            }};
    }
    
    @Override
    public OutboundEndpoint outbound() {
        return new OutboundEndpoint() {
            @Override
            public long outboundBytes() {
                return _trafficCounter.outboundBytes();
            }

            @Override
            public Subscription message(final Observable<? extends Object> message) {
                return _doSetOutboundMessage.call(message);
            }};
    }
    
    private final Func1<Observable<? extends Object>, Subscription> 
        _doSetOutboundMessage = 
            RxFunctions.toFunc1(
            this._funcSelector.callWhenActive(
                RxFunctions.<DefaultHttpInitiator,Subscription>toFunc1_N(
                    DefaultHttpInitiator.class, "doSetOutboundMessage0"))
            .callWhenDestroyed(Func1_N.Util.<DefaultHttpInitiator,Subscription>returnNull()));

    @SuppressWarnings("unused")
    private Subscription doSetOutboundMessage0(
        final Observable<? extends Object> message) {
        if (this._isOutboundSetted.compareAndSet(false, true)) {
            return message.subscribe(
                    doOutboundOnNext(),
                    doOutboundOnError(),
                    doOutboundOnCompleted());
        } else {
            LOG.warn("initiator({}) 's outbound message has setted, ignore new message({})",
                    this, message);
            return null;
        }
    }
    
    private final Action1<Object> doOutboundOnNext() {
        return RxActions.<Object>toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "outboundOnNext0")));
    }
    
    @SuppressWarnings("unused")
    private void outboundOnNext0(final Object msg) {
        this._isOutboundSended.compareAndSet(false, true);
        this._channel.write(ReferenceCountUtil.retain(msg));
    }

    private final Action0 doOutboundOnCompleted() {
        return RxActions.toAction0(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "outboundOnCompleted0")));
    }

    @SuppressWarnings("unused")
    private void outboundOnCompleted0() {
        this._isOutboundCompleted.compareAndSet(false, true);
        this._channel.flush();
        
        //  for initiator outbound is request
//        try {
//            this.fireOnClosed();
//        } catch (Exception e) {
//            LOG.warn("exception when ({}).doClose, detail:{}",
//                    this, ExceptionUtils.exception2detail(e));
//        }
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

    private void hookChannelReadComplete() {
        final ChannelHandler readCompleteNotifier = new ReadCompleteNotifier();
        this._channel.pipeline().addLast(readCompleteNotifier);
        
        addCloseHook(
            RxActions.<HttpInitiator>toAction1(
                RxNettys.actionToRemoveHandler(this._channel, readCompleteNotifier)));
    }

    final Action1<Action1<InboundEndpoint>> _readCompleteInvoker = new Action1<Action1<InboundEndpoint>>() {
        @Override
        public void call(final Action1<InboundEndpoint> onReadComplete) {
            try {
                onReadComplete.call(_inboundEndpoint);
            } catch (Exception e) {
                LOG.warn("exception when initiator({}) invoke onReadComplete({}), detail: {}",
                        DefaultHttpInitiator.this, onReadComplete, ExceptionUtils.exception2detail(e));
            }
        }};
        
    private final class ReadCompleteNotifier extends ChannelInboundHandlerAdapter implements Ordered {
        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
            _onReadCompletes.foreachComponent(_readCompleteInvoker);
        }

        @Override
        public int ordinal() {
            return 1001;
        }
    }
    
    final private InboundEndpoint _inboundEndpoint = new InboundEndpoint() {

        @Override
        public void setAutoRead(final boolean autoRead) {
            _doSetInboundAutoRead.call(autoRead);
        }

        @Override
        public void readMessage() {
            _doReadInbound.call();
        }

        @Override
        public InboundEndpoint addReadCompleteHook(
                final Action1<InboundEndpoint> onReadComplete) {
            _doAddInboundReadCompleteHook.call(onReadComplete);
            return this;
        }

        @Override
        public void removeReadCompleteHook(
                final Action1<InboundEndpoint> onReadComplete) {
            _doRemoveInboundReadCompleteHook.call(onReadComplete);
        }

        @Override
        public long timeToLive() {
            return System.currentTimeMillis() - _createTimeMillis;
        }

        @Override
        public long inboundBytes() {
            return _trafficCounter.inboundBytes();
        }

        @Override
        public Observable<? extends HttpObject> message() {
            return _doGetInboundRequest.call();
        }

        @Override
        public HttpMessageHolder messageHolder() {
            return _inboundHolder;
        }

        @Override
        public int holdingMemorySize() {
            return _inboundHolder.retainedByteBufSize();
        }};
        
    @Override
    public InboundEndpoint inbound() {
        return this._inboundEndpoint;
    }
    
    private final Action1<Action1<InboundEndpoint>> _doAddInboundReadCompleteHook = 
        RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "addInboundReadCompleteHook0"))
        );
    
    @SuppressWarnings("unused")
    private void addInboundReadCompleteHook0(final Action1<InboundEndpoint> onReadComplete) {
        this._onReadCompletes.addComponent(onReadComplete);
    }
    
    private final Action1<Action1<InboundEndpoint>> _doRemoveInboundReadCompleteHook = 
        RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "removeInboundReadCompleteHook0"))
        );
    
    @SuppressWarnings("unused")
    private void removeInboundReadCompleteHook0(final Action1<InboundEndpoint> onReadComplete) {
        this._onReadCompletes.removeComponent(onReadComplete);
    }
    
    private final COWCompositeSupport<Action1<InboundEndpoint>> _onReadCompletes = 
            new COWCompositeSupport<>();
    
    private final Action1<Boolean> _doSetInboundAutoRead = 
        RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.<DefaultHttpInitiator>toAction1_N(
                    DefaultHttpInitiator.class, "setInboundAutoRead0")));
            
    @SuppressWarnings("unused")
    private void setInboundAutoRead0(final boolean autoRead) {
        this._channel.config().setAutoRead(autoRead);
    }
    
    private final Action0 _doReadInbound = 
        RxActions.toAction0(
            this._funcSelector.submitWhenActive(
                RxActions.<DefaultHttpInitiator>toAction1_N(
                    DefaultHttpInitiator.class, "readInbound0")));
    
    @SuppressWarnings("unused")
    private void readInbound0() {
        this._channel.read();
    }
    
    private TrafficCounter buildTrafficCounter(final Channel channel) {
        final TrafficCounterHandler handler = 
                (TrafficCounterHandler)APPLY.TRAFFICCOUNTER.applyTo(channel.pipeline());
        
        addCloseHook(
            RxActions.<HttpInitiator>toAction1(
                RxNettys.actionToRemoveHandler(channel, handler)));
        return handler;
    }
        
    @Override
    public TrafficCounter trafficCounter() {
        return this._trafficCounter;
    }
    
    private final class Closer extends ChannelInboundHandlerAdapter implements Ordered {
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            fireOnClosed();
        }

        @Override
        public int ordinal() {
            return 1000;
        }
    }
    
    private void closeInitiatorWhenChannelInactive() {
        final ChannelHandler closer = new Closer();
        this._channel.pipeline().addLast(closer);
        
        addCloseHook(
            RxActions.<HttpInitiator>toAction1(
                RxNettys.actionToRemoveHandler(this._channel, closer)));
    }

    private Transformer<HttpObject, HttpObject> hookRequest() {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> src) {
                return src.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        if (msg instanceof HttpRequest) {
                            _requestMethod = ((HttpRequest)msg).method().name();
                            _requestUri = ((HttpRequest)msg).uri();
                            _isInboundReceived.compareAndSet(false, true);
                            _isKeepAlive.set(HttpUtil.isKeepAlive((HttpRequest)msg));
                        } else if (!_isInboundReceived.get()) {
                            LOG.warn("trade {} missing http request and recv httpobj {}", 
                                DefaultHttpInitiator.this, msg);
                        }
                    }})
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        _isInboundCompleted.set(true);
                    }})
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        fireOnClosed();
                    }});
            }};
    }
    
    @Override
    public boolean isActive() {
        return this._funcSelector.isActive();
    }

    @Override
    public boolean isEndedWithKeepAlive() {
        return (this._isInboundCompleted.get() 
            && this._isOutboundCompleted.get()
            && this._isKeepAlive.get());
    }

    @Override
    public void abort() {
        doAbort();
    }

    @Override
    public Object transport() {
        return this._channel;
    }
    
    private final Func0<Observable<? extends HttpObject>> _doGetInboundRequest = 
        RxFunctions.toFunc0(
            this._funcSelector.callWhenActive(
                RxFunctions.<DefaultHttpInitiator,Observable<? extends HttpObject>>toFunc1_N(
                    DefaultHttpInitiator.class, "doGetRequest0"))
                .callWhenDestroyed(GET_INBOUND_REQ_ABOUT_ERROR));
    
    @SuppressWarnings("unused")
    private Observable<? extends HttpObject> doGetRequest0() {
        return this._inboundObservableProxy;
    }
    
    private final Observable<HttpObject> _inboundObservableProxy = Observable.create(new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            final Subscriber<? super HttpObject> serializedSubscriber = RxSubscribers.serialized(subscriber);
            if (!serializedSubscriber.isUnsubscribed()) {
                _inboundSubscribers.add(serializedSubscriber);
                serializedSubscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        _inboundSubscribers.remove(serializedSubscriber);
                    }}));
                _inboundObservable.subscribe(serializedSubscriber);
            }
        }});
            
    private final static Func1_N<DefaultHttpInitiator,Observable<? extends HttpObject>> GET_INBOUND_REQ_ABOUT_ERROR = 
        new Func1_N<DefaultHttpInitiator,Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final DefaultHttpInitiator trade,final Object... args) {
                return Observable.error(new RuntimeException("initiator unactived"));
            }};
            

//    @Override
//    public Subscription outboundResponse(final Observable<? extends HttpObject> response) {
//        return this._doSetOutboundMessage.call(response);
//    }
    
//    @Override
//    public boolean readyforOutboundResponse() {
//        return this._funcSelector.isActive() && !this._isOutboundSetted.get();
//    }
    
    @Override
    public HttpInitiator addCloseHook(final Action1<HttpInitiator> onClosed) {
        this._doAddCloseHook.call(onClosed);
        return this;
    }
    
    private final Action1<Action1<HttpInitiator>> _doAddCloseHook = RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "addCloseHook0"))
            .submitWhenDestroyed(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "closeHookNow")));
    
    @SuppressWarnings("unused")
    private void addCloseHook0(final Action1<HttpInitiator> onClosed) {
        this._onCloseds.addComponent(onClosed);
    }
    
    @SuppressWarnings("unused")
    private void closeHookNow(final Action1<HttpInitiator> onClosed) {
        onClosed.call(this);
    }
    
    @Override
    public void removeCloseHook(final Action1<HttpInitiator> onClosed) {
        this._doRemoveCloseHook.call(onClosed);
    }
    
    private final Action1<Action1<HttpInitiator>> _doRemoveCloseHook = RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpInitiator.class, "removeCloseHook0")));
    
    @SuppressWarnings("unused")
    private void removeCloseHook0(final Action1<HttpInitiator> onClosed) {
        this._onCloseds.removeComponent(onClosed);
    }
        
    private void doAbort() {
        this._funcSelector.destroy(
            RxActions.toAction1_N(DefaultHttpInitiator.class, "closeChannelAndFireDoOnClosed"));
    }
    
    @SuppressWarnings("unused")
    private void closeChannelAndFireDoOnClosed() {
        this._channel.close();
        fireOnClosed0();
    }
    
    private void fireOnClosed() {
        this._funcSelector.destroy(
            RxActions.toAction1_N(DefaultHttpInitiator.class, "fireOnClosed0"));
    }

    private final Action1<Action1<HttpInitiator>> _onClosedInvoker = 
        new Action1<Action1<HttpInitiator>>() {
        @Override
        public void call(final Action1<HttpInitiator> onClosed) {
            try {
                onClosed.call(DefaultHttpInitiator.this);
            } catch (Exception e) {
                LOG.warn("exception when initiator({}) invoke onClosed({}), detail: {}",
                    DefaultHttpInitiator.this, onClosed, ExceptionUtils.exception2detail(e));
            }
        }};

    private void fireOnClosed0() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing active initiator[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isOutboundCompleted.get(), this.isEndedWithKeepAlive());
        }
        //  fire all pending subscribers onError with unactived exception
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject>[] subscribers = 
            (Subscriber<? super HttpObject>[])this._inboundSubscribers.toArray(new Subscriber[0]);
        for (Subscriber<? super HttpObject> subscriber : subscribers) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onError(new RuntimeException("initiator unactived"));
                } catch (Exception e) {
                    LOG.warn("exception when invoke ({}).onError, detail: {}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
        this._onCloseds.foreachComponent(_onClosedInvoker);
    }

    private final COWCompositeSupport<Action1<HttpInitiator>> _onCloseds = 
            new COWCompositeSupport<>();
    
    private final HttpMessageHolder _inboundHolder;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final Channel _channel;
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isInboundReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isInboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundSended = new AtomicBoolean(false);
    private final AtomicBoolean _isOutboundCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final Observable<? extends HttpObject> _inboundObservable;
    private final List<Subscriber<? super HttpObject>> _inboundSubscribers = 
            new CopyOnWriteArrayList<>();
}
