/**
 * 
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
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
class DefaultHttpTrade extends DefaultAttributeMap 
    implements HttpTrade,  Comparable<DefaultHttpTrade>  {
    
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
                .append(", request subscriber count=")
                .append(_requestSubscribers.size())
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
    
    private final FuncSelector<DefaultHttpTrade> _funcSelector = 
            new FuncSelector<>(this);
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
//        final Observable<? extends HttpObject> requestObservable,
        final Action1<HttpTrade> ... oncloseds) {
        this(channel, 0, oncloseds);
    }
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final int inboundBlockSize,
        final Action1<HttpTrade> ... oncloseds) {
        this._channel = channel;
        this._trafficCounter = buildTrafficCounter(channel);
        this._inmsgholder = new HttpMessageHolder(inboundBlockSize);
        
        addCloseHook(RxActions.<HttpTrade>toAction1(this._inmsgholder.release()));
        
        this._requestObservable = RxNettys.inboundFromChannel(channel, doOnTerminate())
                .compose(this._inmsgholder.<HttpObject>assembleAndHold())
                .compose(hookRequest())
                .cache()
                .compose(RxNettys.duplicateHttpContent())
                ;
        for (Action1<HttpTrade> hook : oncloseds) {
            addCloseHook(hook);
        }
        
        this._requestObservable.subscribe(
                RxSubscribers.ignoreNext(),
                new Action1<Throwable>() {
                    @Override
                    public void call(final Throwable e) {
                        LOG.warn("Trade({})'s internal request subscriber invoke with onError {}", 
                                DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                    }});
        
        //  在 HTTPOBJ_SUBSCRIBER 添加到 channel.pipeline 后, 再添加 channelInactive 的处理 Handler
        closeTradeWhenChannelInactive();
        hookChannelReadComplete();
    }

    public Action1<Action0> doOnTerminate() {
        return new Action1<Action0>() {
            @Override
            public void call(final Action0 action) {
                addCloseHook(RxActions.<HttpTrade>toAction1(action));
            }};
    }
    
    private void hookChannelReadComplete() {
        final ChannelHandler readCompleteNotifier = new ReadCompleteNotifier();
        this._channel.pipeline().addLast(readCompleteNotifier);
        
        addCloseHook(
            RxActions.<HttpTrade>toAction1(
                RxNettys.actionToRemoveHandler(_channel, readCompleteNotifier)));
    }

    final Action1<Action1<InboundEndpoint>> _readCompleteInvoker = new Action1<Action1<InboundEndpoint>>() {
        @Override
        public void call(final Action1<InboundEndpoint> onReadComplete) {
            try {
                onReadComplete.call(_inboundEndpoint);
            } catch (Exception e) {
                LOG.warn("exception when trade({}) invoke onReadComplete({}), detail: {}",
                        DefaultHttpTrade.this, onReadComplete, ExceptionUtils.exception2detail(e));
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
            return _inmsgholder;
        }

        @Override
        public int holdingMemorySize() {
            return _inmsgholder.retainedByteBufSize();
        }};
        
    @Override
    public InboundEndpoint inbound() {
        return this._inboundEndpoint;
    }
    
    private final Action1<Action1<InboundEndpoint>> _doAddInboundReadCompleteHook = 
        RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "addInboundReadCompleteHook0"))
        );
    
    @SuppressWarnings("unused")
    private void addInboundReadCompleteHook0(final Action1<InboundEndpoint> onReadComplete) {
        this._onReadCompletes.addComponent(onReadComplete);
    }
    
    private final Action1<Action1<InboundEndpoint>> _doRemoveInboundReadCompleteHook = 
        RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "removeInboundReadCompleteHook0"))
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
                RxActions.<DefaultHttpTrade>toAction1_N(
                    DefaultHttpTrade.class, "setInboundAutoRead0")));
            
    @SuppressWarnings("unused")
    private void setInboundAutoRead0(final boolean autoRead) {
        this._channel.config().setAutoRead(autoRead);
    }
    
    private final Action0 _doReadInbound = 
        RxActions.toAction0(
            this._funcSelector.submitWhenActive(
                RxActions.<DefaultHttpTrade>toAction1_N(
                    DefaultHttpTrade.class, "readInbound0")));
    
    @SuppressWarnings("unused")
    private void readInbound0() {
        this._channel.read();
    }
    
    private TrafficCounter buildTrafficCounter(final Channel channel) {
        final TrafficCounterHandler handler = 
                (TrafficCounterHandler)APPLY.TRAFFICCOUNTER.applyTo(channel.pipeline());
        
        addCloseHook(
            RxActions.<HttpTrade>toAction1(
                RxNettys.actionToRemoveHandler(channel, handler)));
        return handler;
    }
        
    @Override
    public TrafficCounter trafficCounter() {
        return this._trafficCounter;
    }
    
    private final class TradeCloser extends ChannelInboundHandlerAdapter implements Ordered {
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            doClose();
        }

        @Override
        public int ordinal() {
            return 1000;
        }
    }
    
    private void closeTradeWhenChannelInactive() {
        final ChannelHandler tradeCloser = new TradeCloser();
        this._channel.pipeline().addLast(tradeCloser);
        
        addCloseHook(
            RxActions.<HttpTrade>toAction1(
                RxNettys.actionToRemoveHandler(_channel, tradeCloser)));
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
                        doClose();
                    }});
            }};
    }
    
    @Override
    public boolean isActive() {
        return this._funcSelector.isActive();
    }

    public boolean isEndedWithKeepAlive() {
        return (this._isRequestCompleted.get() 
            && this._isResponseCompleted.get()
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
                RxFunctions.<DefaultHttpTrade,Observable<? extends HttpObject>>toFunc1_N(
                    DefaultHttpTrade.class, "doGetRequest"))
                .callWhenDestroyed(GET_INBOUND_REQ_ABOUT_ERROR));
    
    @SuppressWarnings("unused")
    private Observable<? extends HttpObject> doGetRequest() {
        return this._requestObservableProxy;
    }
    
    private final Observable<HttpObject> _requestObservableProxy = Observable.create(new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            final Subscriber<? super HttpObject> serializedSubscriber = RxSubscribers.serialized(subscriber);
            if (!serializedSubscriber.isUnsubscribed()) {
                _requestSubscribers.add(serializedSubscriber);
                serializedSubscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        _requestSubscribers.remove(serializedSubscriber);
                    }}));
                _requestObservable.subscribe(serializedSubscriber);
            }
        }});
            
    private final static Func1_N<DefaultHttpTrade,Observable<? extends HttpObject>> GET_INBOUND_REQ_ABOUT_ERROR = 
        new Func1_N<DefaultHttpTrade,Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final DefaultHttpTrade trade,final Object... args) {
                return Observable.error(new RuntimeException("trade unactived"));
            }};

    @Override
    public Subscription outboundResponse(final Observable<? extends HttpObject> response) {
        return this._doSetOutboundResponse.call(response);
    }
    
    private final Func1<Observable<? extends HttpObject>, Subscription> 
        _doSetOutboundResponse = 
            RxFunctions.toFunc1(
                this._funcSelector.callWhenActive(RxFunctions.<DefaultHttpTrade,Subscription>toFunc1_N(
                        DefaultHttpTrade.class, "doSetOutboundResponse"))
                .callWhenDestroyed(Func1_N.Util.<DefaultHttpTrade,Subscription>returnNull()));
    
    @SuppressWarnings("unused")
    private Subscription doSetOutboundResponse(
        final Observable<? extends HttpObject> response) {
        if (this._isResponseSetted.compareAndSet(false, true)) {
            return response.subscribe(
                    actionResponseOnNext(),
                    actionResponseOnError(),
                    actionResponseOnCompleted());
        } else {
            LOG.warn("trade({}) 's outboundResponse has setted, ignore new response({})",
                    this, response);
            return null;
        }
    }

    private final Action0 actionResponseOnCompleted() {
        return RxActions.toAction0(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnCompleted")));
    }

    @SuppressWarnings("unused")
    private void respOnCompleted() {
        this._isResponseCompleted.compareAndSet(false, true);
        this._channel.flush();
        try {
            this.doClose();
        } catch (Exception e) {
            LOG.warn("exception when ({}).doClose, detail:{}",
                    this, ExceptionUtils.exception2detail(e));
        }
    }

    private final Action1<HttpObject> actionResponseOnNext() {
        return RxActions.<HttpObject>toAction1(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnNext")));
    }
    
    @SuppressWarnings("unused")
    private void respOnNext(final HttpObject msg) {
        this._isResponseSended.compareAndSet(false, true);
        this._channel.write(ReferenceCountUtil.retain(msg));
    }

    private final Action1<Throwable> actionResponseOnError() {
        return RxActions.<Throwable>toAction1(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnError")));
    }
    
    @SuppressWarnings("unused")
    private void respOnError(final Throwable e) {
        LOG.warn("trade({})'s responseObserver.onError, default action is invoke doAbort() and detail:{}",
                DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
        doAbort();
    }
        
    @Override
    public boolean readyforOutboundResponse() {
        return this._funcSelector.isActive() && !this._isResponseSetted.get();
    }
    
    @Override
    public HttpTrade addCloseHook(final Action1<HttpTrade> onClosed) {
        this._doAddCloseHook.call(onClosed);
        return this;
    }
    
    private final Action1<Action1<HttpTrade>> _doAddCloseHook = RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "addCloseHook0"))
            .submitWhenDestroyed(
                RxActions.toAction1_N(DefaultHttpTrade.class, "closeHookNow")));
    
    @SuppressWarnings("unused")
    private void addCloseHook0(final Action1<HttpTrade> onClosed) {
        this._onCloseds.addComponent(onClosed);
    }
    
    @SuppressWarnings("unused")
    private void closeHookNow(final Action1<HttpTrade> onClosed) {
        onClosed.call(this);
    }
    
    @Override
    public void removeCloseHook(final Action1<HttpTrade> onClosed) {
        this._doRemoveCloseHook.call(onClosed);
    }
    
    private final Action1<Action1<HttpTrade>> _doRemoveCloseHook = RxActions.toAction1(
            this._funcSelector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "removeCloseHook0")));
    
    @SuppressWarnings("unused")
    private void removeCloseHook0(final Action1<HttpTrade> onClosed) {
        this._onCloseds.removeComponent(onClosed);
    }
        
    private void doAbort() {
        this._funcSelector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "closeChannelAndFireDoOnClosed"));
    }
    
    @SuppressWarnings("unused")
    private void closeChannelAndFireDoOnClosed() {
        this._channel.close();
        fireDoOnClosed();
    }
    
    private void doClose() {
        this._funcSelector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "fireDoOnClosed"));
    }

    private final Action1<Action1<HttpTrade>> _onClosedInvoker = new Action1<Action1<HttpTrade>>() {
        @Override
        public void call(final Action1<HttpTrade> onClosed) {
            try {
                onClosed.call(DefaultHttpTrade.this);
            } catch (Exception e) {
                LOG.warn("exception when trade({}) invoke onClosed({}), detail: {}",
                        DefaultHttpTrade.this, onClosed, ExceptionUtils.exception2detail(e));
            }
        }};

    private void fireDoOnClosed() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isResponseCompleted.get(), this.isEndedWithKeepAlive());
        }
        //  fire all pending subscribers onError with unactived exception
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject>[] subscribers = 
            (Subscriber<? super HttpObject>[])this._requestSubscribers.toArray(new Subscriber[0]);
        for (Subscriber<? super HttpObject> subscriber : subscribers) {
            if (!subscriber.isUnsubscribed()) {
                try {
                    subscriber.onError(new RuntimeException("trade unactived"));
                } catch (Exception e) {
                    LOG.warn("exception when invoke ({}).onError, detail: {}",
                            subscriber, ExceptionUtils.exception2detail(e));
                }
            }
        }
        this._onCloseds.foreachComponent(_onClosedInvoker);
    }

    private final COWCompositeSupport<Action1<HttpTrade>> _onCloseds = 
            new COWCompositeSupport<>();
    
    private final HttpMessageHolder _inmsgholder;
    private final long _createTimeMillis = System.currentTimeMillis();
    private final Channel _channel;
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final Observable<? extends HttpObject> _requestObservable;
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = 
            new CopyOnWriteArrayList<>();
}
