/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServer.HttpTrade;
import org.jocean.idiom.ActiveHolder;
import org.jocean.idiom.COWCompositeSupport;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.Action1_N;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.ActionN;
import rx.functions.Func0;
import rx.functions.Func2;
import rx.functions.FuncN;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade implements HttpTrade {
    
    private static final Func0<FullHttpRequest> RETAIN_REQ_RETURN_NULL = new Func0<FullHttpRequest>() {
        @Override
        public FullHttpRequest call() {
            return null;
        }};
    private static final Action1<HttpObject> NOP_ON_NEXT = new Action1<HttpObject>() {
        @Override
        public void call(HttpObject t) {
        }};
    private static final Action1<Throwable> NOP_ON_ERROR = new Action1<Throwable>() {
        @Override
        public void call(Throwable t) {
        }};
        
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [request subscriber count=")
                .append(_requestSubscribers.size())
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
    
    DefaultHttpTrade(
            final Channel channel, 
            final Observable<? extends HttpObject> requestObservable) {
        this(channel, requestObservable, new HttpObjectHolder(-1));
    }
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final Observable<? extends HttpObject> requestObservable,
        final HttpObjectHolder holder,
        final Action1<HttpTrade> ... doOnCloseds) {
        this._channel = channel;
        final Observable<? extends HttpObject> hookedObservable = requestObservable
            .doOnNext(new Action1<HttpObject>() {
                @Override
                public void call(final HttpObject msg) {
                    _isRequestReceived.compareAndSet(false, true);
                  if (msg instanceof HttpRequest) {
                      _isKeepAlive.set(HttpHeaders.isKeepAlive((HttpRequest)msg));
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
        if (null!=holder) {
            this._requestObservable = hookedObservable.flatMap(holder.composite()).cache();
            this._retainFullRequest = holder.retainFullHttpRequest();
            doOnClosed(RxActions.<HttpTrade>toAction1(holder.destroy()));
        } else {
            this._requestObservable = hookedObservable.publish().refCount();
            this._retainFullRequest = RETAIN_REQ_RETURN_NULL;
        }
        for (Action1<HttpTrade> onclosed : doOnCloseds) {
            doOnClosed(onclosed);
        }
//        //  TODO when to unsubscribe ?
        this._requestObservable.subscribe(NOP_ON_NEXT, NOP_ON_ERROR);
    }

    private Observable<? extends HttpObject> internalRequestObservable() {
        return _requestObservableProxy;
    }

    @Override
    public FullHttpRequest retainFullHttpRequest() {
        return this._retainFullRequest.call();
    }
    
    @Override
    public boolean isActive() {
        return this._activeHolder.isActive();
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

    @Override
    public boolean readyforOutboundResponse() {
        synchronized(this._subscriptionOfResponse) {
            //  对 outboundResponse 方法加锁
            final Subscription oldsubsc =  this._subscriptionOfResponse.get();
            return this._activeHolder.isActive() && (null==oldsubsc ||
                (oldsubsc.isUnsubscribed() && !this._isResponseSended.get()));
        }
    }
    
    @Override
    public HttpTrade doOnClosed(final Action1<HttpTrade> onClosed) {
        this._actionDoOnClosed.call(onClosed);
        return this;
    }
    
    @Override
    public void undoOnClosed(final Action1<HttpTrade> onClosed) {
        this._actionUndoOnClosed.call(onClosed);
    }
    
    private void doAbort() {
        this._activeHolder.destroy(DO_ABORT_TRADE);
    }
    
    private void doClose() {
        this._activeHolder.destroy(DO_CLOSE_TRADE);
    }
    
    private void fireDoOnClosed() {
        //  fire all pending subscribers onError with unactived exception
        @SuppressWarnings("unchecked")
        final Subscriber<? super HttpObject>[] subscribers = (Subscriber<? super HttpObject>[])this._requestSubscribers.toArray(new Subscriber[0]);
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

    private final static Func1_N<DefaultHttpTrade, Observable<? extends HttpObject>> GET_INBOUND_REQ_WHEN_ACTIVE = 
        new Func1_N<DefaultHttpTrade, Observable<? extends HttpObject>>() {
        @Override
        public Observable<? extends HttpObject> call(final DefaultHttpTrade trade,
                final Object... args) {
            return trade.internalRequestObservable();
        }};
    private final static FuncN<Observable<? extends HttpObject>> GET_INBOUND_REQ_ABOUT_ERROR = 
        new FuncN<Observable<? extends HttpObject>>() {
        @Override
        public Observable<? extends HttpObject> call(final Object... args) {
            return Observable.error(new RuntimeException("trade unactived"));
        }};
        
    private final static Func1_N<DefaultHttpTrade, Subscription> SET_OUTBOUND_RESP_WHEN_ACTIVE = 
            new Func1_N<DefaultHttpTrade, Subscription>() {
            @Override
            public Subscription call(final DefaultHttpTrade trade, final Object... args) {
                final Observable<? extends HttpObject> response = 
                        JOArrays.<Observable<? extends HttpObject>>takeArgAs(0, args);
                final Action1<Throwable> onError = 
                        JOArrays.<Action1<Throwable>>takeArgAs(1, args);
                synchronized(trade._subscriptionOfResponse) {
                    //  对 outboundResponse 方法加锁
                    final Subscription oldsubsc =  trade._subscriptionOfResponse.get();
                    if (null==oldsubsc ||
                        (oldsubsc.isUnsubscribed() && !trade._isResponseSended.get())) {
                        final Subscription newsubsc = response.subscribe(
                                trade._actionResponseOnNext,
                                null!=onError ? onError : trade._responseOnError,
                                trade._actionResponseOnCompleted);
                        trade._subscriptionOfResponse.set(newsubsc);
                        return newsubsc;
                    }
                }
                return null;
            }};
            
    private final static FuncN<Subscription> RETURN_NULL_SUBSCRIPTION = new FuncN<Subscription>() {
        @Override
        public Subscription call(final Object... args) {
            return null;
        }};

    private final static Action1_N<DefaultHttpTrade> ADD_ON_CLOSED_WHEN_ACTIVE = new Action1_N<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade, final Object...args) {
            trade._onClosedActions.addComponent(JOArrays.<Action1<HttpTrade>>takeArgAs(0, args));
        }};
        
    private static final Action1_N<DefaultHttpTrade> REMOVE_DO_ON_CLOSE_WHEN_ACTIVE= new Action1_N<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade,final Object...args) {
          trade._onClosedActions.removeComponent(JOArrays.<Action1<HttpTrade>>takeArgAs(0, args));
        }};
        
    private static final Action1<DefaultHttpTrade> DO_ABORT_TRADE = new Action1<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                        trade._channel, trade._isResponseCompleted.get(), trade.isEndedWithKeepAlive());
            }
            trade._channel.close();
            trade.fireDoOnClosed();
        }};
        
    private static final Action1<DefaultHttpTrade> DO_CLOSE_TRADE = new Action1<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                        trade._channel, trade._isResponseCompleted.get(), trade.isEndedWithKeepAlive());
            }
            trade.fireDoOnClosed();
        }};
            
    private static final Action1_N<DefaultHttpTrade> DO_RESP_ON_COMPLETED_WHEN_ACTIVE = new Action1_N<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade, final Object...args) {
            trade._isResponseSended.compareAndSet(false, true);
            trade._isResponseCompleted.compareAndSet(false, true);
            trade._channel.flush();
            try {
                trade.doClose();
            } catch (Exception e) {
                LOG.warn("exception when ({}).doClose, detail:{}",
                        trade, ExceptionUtils.exception2detail(e));
            }
        }};
            
    private static final Action1_N<DefaultHttpTrade> DO_RESP_ON_NEXT_WHEN_ACTIVE = new Action1_N<DefaultHttpTrade>() {
        @Override
        public void call(final DefaultHttpTrade trade, final Object...args) {
            trade._isResponseSended.compareAndSet(false, true);
            trade._channel.write(ReferenceCountUtil.retain(JOArrays.<HttpObject>takeArgAs(0, args)));
        }};
            
    private final COWCompositeSupport<Action1<HttpTrade>> _onClosedActions = 
            new COWCompositeSupport<Action1<HttpTrade>>();
    
    private final ActiveHolder<DefaultHttpTrade> _activeHolder = 
            new ActiveHolder<>(this);
    
    private final Func0<FullHttpRequest> _retainFullRequest;
    private final Channel _channel;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final AtomicReference<Subscription> _subscriptionOfResponse = 
            new AtomicReference<Subscription>(null);
    
    private final Func0<Observable<? extends HttpObject>> _funcGetInboundRequest = 
            RxFunctions.toFunc0(this._activeHolder.callWhenActive(GET_INBOUND_REQ_WHEN_ACTIVE)
                .callWhenDestroyed(GET_INBOUND_REQ_ABOUT_ERROR));
    
    private final Func2<Observable<? extends HttpObject>, Action1<Throwable>, Subscription> 
        _funcSetOutboundResponse = 
            RxFunctions.toFunc2(this._activeHolder.callWhenActive(SET_OUTBOUND_RESP_WHEN_ACTIVE)
                .callWhenDestroyed(RETURN_NULL_SUBSCRIPTION));
    
    private final Action1<Action1<HttpTrade>> _actionDoOnClosed = RxActions.toAction1(
            this._activeHolder.submitWhenActive(ADD_ON_CLOSED_WHEN_ACTIVE)
                .submitWhenDestroyed(new ActionN() {
                    @Override
                    public void call(final Object...args) {
                        JOArrays.<Action1<HttpTrade>>takeArgAs(0, args).call(DefaultHttpTrade.this);
                    }}));

    private final Action1<Action1<HttpTrade>> _actionUndoOnClosed = RxActions.toAction1(
            this._activeHolder.submitWhenActive(REMOVE_DO_ON_CLOSE_WHEN_ACTIVE));
    
    private final Action0 _actionResponseOnCompleted = 
            RxActions.toAction0(this._activeHolder.submitWhenActive(DO_RESP_ON_COMPLETED_WHEN_ACTIVE));

    private final Action1<HttpObject> _actionResponseOnNext = 
            RxActions.toAction1(this._activeHolder.submitWhenActive(DO_RESP_ON_NEXT_WHEN_ACTIVE));
    
    private final Action1<Throwable> _responseOnError = new Action1<Throwable>() {
        @Override
        public void call(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, default action is invoke doAbort() and detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            doAbort();
        }};
        
    private final Observable<? extends HttpObject> _requestObservable;
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = 
            new CopyOnWriteArrayList<>();
    private final Observable<HttpObject> _requestObservableProxy = Observable.create(new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                _requestSubscribers.add(subscriber);
                subscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        _requestSubscribers.remove(subscriber);
                    }}));
                _requestObservable.subscribe(subscriber);
            }
        }});
}
