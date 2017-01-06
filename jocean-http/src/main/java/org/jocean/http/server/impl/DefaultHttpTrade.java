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

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
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
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func2;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade implements HttpTrade,  Comparable<DefaultHttpTrade>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultHttpTrade o) {
        return this._id - o._id;
    }
    
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(_createTime))
                .append(", request subscriber count=")
                .append(_requestSubscribers.size())
                .append(", currentResponseIdx=").append(_responseIdx.get())
                .append(", isRequestReceived=").append(_isRequestReceived.get())
                .append(", isRequestCompleted=").append(_isRequestCompleted.get())
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
    
    private final FuncSelector<DefaultHttpTrade> _selector = 
            new FuncSelector<>(this);
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final Observable<? extends HttpObject> requestObservable,
        final Action1<HttpTrade> ... doOnCloseds) {
        this._channel = channel;
        this._requestObservable = requestObservable
                .compose(hookRequest())
                .share()
                .compose(RxNettys.duplicateHttpContent());
                ;
                
        closeTradeWhenChannelInactive();
        
        for (Action1<HttpTrade> onclosed : doOnCloseds) {
            doOnClosed(onclosed);
        }
//        //  TODO when to unsubscribe ?
        this._requestObservable.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
    }

    private void closeTradeWhenChannelInactive() {
        final ChannelHandler doCloseWhenChannelInactive = 
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    doClose();
                }
            };
        this._channel.pipeline().addLast(doCloseWhenChannelInactive);
        doOnClosed(new Action1<HttpTrade>() {
            @Override
            public void call(final HttpTrade trade) {
                _channel.pipeline().remove(doCloseWhenChannelInactive);
            }});
    }

    private Transformer<HttpObject, HttpObject> hookRequest() {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> src) {
                return src.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        _isRequestReceived.compareAndSet(false, true);
                      if (msg instanceof HttpRequest) {
                          _isKeepAlive.set(HttpUtil.isKeepAlive((HttpRequest)msg));
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
        return this._selector.isActive();
    }

    @Override
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
    
    @Override
    public Observable<? extends HttpObject> inboundRequest() {
        return this._funcGetInboundRequest.call();
    }

    private final Func0<Observable<? extends HttpObject>> _funcGetInboundRequest = 
        RxFunctions.toFunc0(
            this._selector.callWhenActive(
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
        return outboundResponse(response, null);
    }
    
    @Override
    public Subscription outboundResponse(
            final Observable<? extends HttpObject> response,
            final Action1<Throwable> onError) {
        return this._funcSetOutboundResponse.call(response, onError);
    }

    private final Func2<Observable<? extends HttpObject>, Action1<Throwable>, Subscription> 
        _funcSetOutboundResponse = 
            RxFunctions.toFunc2(
                this._selector.callWhenActive(RxFunctions.<DefaultHttpTrade,Subscription>toFunc1_N(
                        DefaultHttpTrade.class, "doSetOutboundResponse"))
                .callWhenDestroyed(Func1_N.Util.<DefaultHttpTrade,Subscription>returnNull()));
    
    @SuppressWarnings("unused")
    private Subscription doSetOutboundResponse(
        final Observable<? extends HttpObject> response,
        final Action1<Throwable> onError) {
        if (!isResponseStarted()) {
            final int responseIdx = updateResponseIdx();
            return response.subscribe(
                    actionResponseOnNext(responseIdx),
                    null!=onError 
                        ? wrapResponseOnError(responseIdx, onError) 
                        : actionResponseOnError(responseIdx),
                    actionResponseOnCompleted(responseIdx));
        } else {
            LOG.warn("trade({}) 's outboundResponse has taken effect, ignore new response({})",
                    this, response);
            return null;
        }
    }

    private boolean isResponseStarted() {
        return this._isResponseSended.get() 
            || this._isResponseCompleted.get();
    }

    private int updateResponseIdx() {
        return this._responseIdx.incrementAndGet();
    }
    
    private final AtomicInteger _responseIdx = new AtomicInteger(0);
    
    private final Action0 actionResponseOnCompleted(final int responseIdx) {
        return RxActions.bindParameter(RxActions.<Integer>toAction1(
            this._selector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnCompleted"))),
            responseIdx);
    }

    @SuppressWarnings("unused")
    private void respOnCompleted(final int responseIdx) {
        if (this._responseIdx.get() == responseIdx) {
            this._isResponseCompleted.compareAndSet(false, true);
            this._channel.flush();
            try {
                this.doClose();
            } catch (Exception e) {
                LOG.warn("exception when ({}).doClose, detail:{}",
                        this, ExceptionUtils.exception2detail(e));
            }
        } else {
            LOG.warn("onCompleted 's id({}) NOT EQUALS current response idx:{}, just ignore."
                    , responseIdx, this._responseIdx.get());
        }
    }

    private final Action1<HttpObject> actionResponseOnNext(final int responseIdx) {
        return RxActions.bindParameter(
                RxActions.<Integer, HttpObject>toAction2(
                    this._selector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnNext"))),
                responseIdx );
    }
    
    @SuppressWarnings("unused")
    private void respOnNext(final int responseIdx, final HttpObject msg) {
        if (this._responseIdx.get() == responseIdx) {
            this._isResponseSended.compareAndSet(false, true);
            this._channel.write(ReferenceCountUtil.retain(msg));
        } else {
            LOG.warn("msg ({})'s id({}) NOT EQUALS current response idx:{}, just ignore."
                    , msg, responseIdx, this._responseIdx.get());
        }
    }

    private final Action1<Throwable> wrapResponseOnError(final int responseIdx, final Action1<Throwable> onError) {
        return new Action1<Throwable>() {
            @Override
            public void call(final Throwable e) {
                if (_responseIdx.get() == responseIdx) {
                    LOG.warn("trade({})'s responseObserver.onError, custom action({}) is invoke and detail:{}",
                            DefaultHttpTrade.this, onError, ExceptionUtils.exception2detail(e));
                    onError.call(e);
                } else {
                    LOG.warn("onError ({})'s id({}) NOT EQUALS current response idx:{}, just ignore."
                            , responseIdx, _responseIdx.get());
                }
            }};
    }
    
    private final Action1<Throwable> actionResponseOnError(final int responseIdx) {
        return RxActions.bindParameter(
                RxActions.<Integer, Throwable>toAction2(
                    this._selector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnError"))),
                responseIdx);
    }
    
    @SuppressWarnings("unused")
    private void respOnError(final int responseIdx, final Throwable e) {
        if (this._responseIdx.get() == responseIdx) {
            LOG.warn("trade({})'s responseObserver.onError, default action is invoke doAbort() and detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            doAbort();
        } else {
            LOG.warn("onError ({})'s id({}) NOT EQUALS current response idx:{}, just ignore."
                    , responseIdx, this._responseIdx.get());
        }
    }
        
    @Override
    public boolean readyforOutboundResponse() {
        return this._selector.isActive() && !isResponseStarted();
    }
    
    @Override
    public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed) {
        this._actionDoOnClosed.call(onClosed);
        return this;
    }
    
    private final Action1<Action1<HttpTrade>> _actionDoOnClosed = RxActions.toAction1(
            this._selector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "internalDoOnClosed"))
            .submitWhenDestroyed(
                RxActions.toAction1_N(DefaultHttpTrade.class, "fireOnClosedOf")));
    
    @SuppressWarnings("unused")
    private void internalDoOnClosed(final Action1<HttpTrade> onClosed) {
        this._onClosedActions.addComponent(onClosed);
    }
    
    @SuppressWarnings("unused")
    private void fireOnClosedOf(final Action1<HttpTrade> onClosed) {
        onClosed.call(this);
    }
    
    @Override
    public void undoOnClosed(final Action1<HttpTrade> onClosed) {
        this._actionUndoOnClosed.call(onClosed);
    }
    
    private final Action1<Action1<HttpTrade>> _actionUndoOnClosed = RxActions.toAction1(
            this._selector.submitWhenActive(
                RxActions.toAction1_N(DefaultHttpTrade.class, "internalUndoOnClosed")));
    
    @SuppressWarnings("unused")
    private void internalUndoOnClosed(final Action1<HttpTrade> onClosed) {
        this._onClosedActions.removeComponent(onClosed);
    }
        
    private void doAbort() {
        this._selector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "closeChannelAndFireDoOnClosed"));
    }
    
    @SuppressWarnings("unused")
    private void closeChannelAndFireDoOnClosed() {
        this._channel.close();
        fireDoOnClosed();
    }
    
    private void doClose() {
        this._selector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "fireDoOnClosed"));
    }
            
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
        this._onClosedActions.foreachComponent(new Action1<Action1<HttpTrade>>() {
            @Override
            public void call(final Action1<HttpTrade> onClosed) {
                try {
                    onClosed.call(DefaultHttpTrade.this);
                } catch (Exception e) {
                    LOG.warn("exception when trade({}) invoke onClosed({}), detail: {}",
                            DefaultHttpTrade.this, onClosed, ExceptionUtils.exception2detail(e));
                }
            }});
    }

    private final COWCompositeSupport<Action1<HttpTrade>> _onClosedActions = 
            new COWCompositeSupport<Action1<HttpTrade>>();
    
    private final Date _createTime = new Date();
    private final Channel _channel;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final Observable<? extends HttpObject> _requestObservable;
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = 
            new CopyOnWriteArrayList<>();
}
