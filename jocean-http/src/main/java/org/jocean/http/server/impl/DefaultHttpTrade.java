/**
 * 
 */
package org.jocean.http.server.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.server.HttpServer.HttpTrade;
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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
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
import rx.observers.SerializedSubscriber;
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
    
    private final FuncSelector<DefaultHttpTrade> _selector = 
            new FuncSelector<>(this);
    
    DefaultHttpTrade(
            final Channel channel, 
            final Observable<? extends HttpObject> requestObservable) {
        this(channel, requestObservable, new HttpObjectHolder(0));
    }
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final Observable<? extends HttpObject> requestObservable,
        final HttpObjectHolder holder,
        final Action1<HttpTrade> ... doOnCloseds) {
        this._channel = channel;
        if (null!=holder) {
            this._requestObservable = requestObservable
                    .compose(hookRequest())
                    .flatMap(holder.assembleAndHold())
                    .cache();
            this._retainFullRequest = new Func0<FullHttpRequest>() {
                @Override
                public FullHttpRequest call() {
                    return holder.visitHttpObjects(RxNettys.BUILD_FULL_REQUEST);
                }};
            doOnClosed(RxActions.<HttpTrade>toAction1(holder.release()));
        } else {
            this._requestObservable = requestObservable
                    .compose(hookRequest())
                    .publish()
                    .refCount();
            this._retainFullRequest = RETAIN_REQ_RETURN_NULL;
        }
        for (Action1<HttpTrade> onclosed : doOnCloseds) {
            doOnClosed(onclosed);
        }
//        //  TODO when to unsubscribe ?
        this._requestObservable.subscribe(RxSubscribers.nopOnNext(), RxSubscribers.nopOnError());
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
            }};
    }
    
    @Override
    public FullHttpRequest retainFullHttpRequest() {
        return this._retainFullRequest.call();
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
    
    private static <T> Subscriber<T> serialized(final Subscriber<T> subscriber) {
        if (subscriber instanceof SerializedSubscriber) {
            return subscriber;
        } else {
            return new SerializedSubscriber<T>(subscriber);
        }
    }
    
    private final Observable<HttpObject> _requestObservableProxy = Observable.create(new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            final Subscriber<? super HttpObject> serializedSubscriber = serialized(subscriber);
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
        synchronized(this._subscriptionOfResponse) {
            //  对 outboundResponse 方法加锁
            final Subscription oldsubsc =  this._subscriptionOfResponse.get();
            if (null==oldsubsc ||
                (oldsubsc.isUnsubscribed() && !this._isResponseSended.get())) {
                final Subscription newsubsc = response.subscribe(
                        this._actionResponseOnNext,
                        null!=onError ? onError : this._responseOnError,
                        this._actionResponseOnCompleted);
                this._subscriptionOfResponse.set(newsubsc);
                return newsubsc;
            }
        }
        return null;
    }

    private final Action0 _actionResponseOnCompleted = RxActions.toAction0(
        this._selector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnCompleted")));

    @SuppressWarnings("unused")
    private void respOnCompleted() {
        this._isResponseSended.compareAndSet(false, true);
        this._isResponseCompleted.compareAndSet(false, true);
        this._channel.flush();
        try {
            this.doClose();
        } catch (Exception e) {
            LOG.warn("exception when ({}).doClose, detail:{}",
                    this, ExceptionUtils.exception2detail(e));
        }
    }

    private final Action1<HttpObject> _actionResponseOnNext = RxActions.toAction1(
        this._selector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnNext")));
    
    @SuppressWarnings("unused")
    private void respOnNext(final HttpObject msg) {
        this._isResponseSended.compareAndSet(false, true);
        this._channel.write(ReferenceCountUtil.retain(msg));
    }

    private final Action1<Throwable> _responseOnError = new Action1<Throwable>() {
        @Override
        public void call(final Throwable e) {
            LOG.warn("trade({})'s responseObserver.onError, default action is invoke doAbort() and detail:{}",
                    DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
            doAbort();
        }};
        
    @Override
    public boolean readyforOutboundResponse() {
        if (!isActive()) {
            return false;
        } else {
            synchronized(this._subscriptionOfResponse) {
                //  对 outboundResponse 方法加锁
                final Subscription oldsubsc =  this._subscriptionOfResponse.get();
                return this._selector.isActive() && (null==oldsubsc ||
                    (oldsubsc.isUnsubscribed() && !this._isResponseSended.get()));
            }
        }
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
    
    private final Func0<FullHttpRequest> _retainFullRequest;
    private final Channel _channel;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
    private final AtomicReference<Subscription> _subscriptionOfResponse = 
            new AtomicReference<Subscription>(null);
    private final Observable<? extends HttpObject> _requestObservable;
    private final List<Subscriber<? super HttpObject>> _requestSubscribers = 
            new CopyOnWriteArrayList<>();
}
