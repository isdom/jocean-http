/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.FlowLifecycleListener;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.event.api.internal.DefaultInvoker;
import org.jocean.event.api.internal.EventInvoker;
import org.jocean.http.server.HttpTrade;
import org.jocean.http.server.InboundFeature;
import org.jocean.http.server.impl.DefaultHttpServer.ChannelRecycler;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;

/**
 * @author isdom
 *
 */
public class DefaultHttpTrade implements HttpTrade {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    private final AtomicReference<Channel> _channelRef = new AtomicReference<>(null);
    private final HandlersClosure _closure;
    private final EventReceiver _requestReceiver;
    private final EventReceiver _responseReceiver;
    private volatile boolean _isKeepAlive = false;
    private final ChannelRecycler _channelRecycler;
    
    public DefaultHttpTrade(
            final Channel channel, 
            final EventEngine engine,
            final ChannelRecycler channelRecycler) {
        this._channelRecycler = channelRecycler;
        this._channelRef.set(channel);
        this._closure = Nettys.channelHandlersClosure(channel);
        Nettys.insertHandler(
            channel.pipeline(),
            "work",
            this._closure.call(new WorkHandler()),
            InboundFeature.TO_ORDINAL);
        this._requestReceiver = engine.create(this.toString() + ".req", 
            this.REQ_ACTIVED, this.REQ_ACTIVED);
        this._responseReceiver = engine.create(this.toString() + ".resp",
            new WAIT_RESP(0, null)
                .handler(DefaultInvoker.invokers(new RESP_DETACHABLE(null)))
                .freeze(),
            new FlowLifecycleListener() {
                @Override
                public void afterEventReceiverCreated(EventReceiver receiver)
                        throws Exception {
                }
                @Override
                public void afterFlowDestroy() throws Exception {
                    detachAll();
                }});
    }

    private boolean isChannelActived() {
        final Channel channel = this._channelRef.get();
        return null!=channel ? channel.isActive() : false;
    }

    private Channel detachChannel() {
       return this._channelRef.getAndSet(null);
    }
    
    private void detachAll() {
        final Channel channel = detachChannel();
        if (null!=channel) {
            channel.close();
        }
        this._requestReceiver.acceptEvent(ON_REQUEST_ERROR, REQUEST_EXPIRED);
        this._responseReceiver.acceptEvent("detach");
    }
    
    @Override
    public void close() throws IOException {
        detachAll();
    }
    
    private OnSubscribe<HttpObject> _onSubscribeRequest = new OnSubscribe<HttpObject>() {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                if (isChannelActived()) {
                    _requestReceiver.acceptEvent(ADDSUBSCRIBER_EVENT, subscriber);
                } else {
                    subscriber.onError(REQUEST_EXPIRED);
                }
            }
        }
    };

    @Override
    public Observable<HttpObject> request() {
        return Observable.create(this._onSubscribeRequest);
    }

    @Override
    public void response(final Observable<HttpObject> response) {
        this._responseReceiver.acceptEvent(SET_RESPONSE, response);
    }
    
    @Override
    public FullHttpRequest retainFullHttpRequest() {
        return this.REQ_ACTIVED.retainFullHttpRequest();
    }
    
    private static final Throwable REQUEST_EXPIRED = 
            new RuntimeException("request expired");
    private static final String ADD_SUBSCRIBER = "addSubscriber";
    private static final String ON_HTTP_OBJECT = "onHttpObject";
    private static final String ON_REQUEST_ERROR = "onChannelError";
    
    private static final PairedGuardEventable ONHTTPOBJ_EVENT = 
            new PairedGuardEventable(Nettys._NETTY_REFCOUNTED_GUARD, ON_HTTP_OBJECT);
    
    private static final AbstractUnhandleAware ADDSUBSCRIBER_EVENT = 
            new AbstractUnhandleAware(ADD_SUBSCRIBER) {
        @Override
        public void onEventUnhandle(final String event, final Object... args)
                throws Exception {
            @SuppressWarnings("unchecked")
            final Subscriber<? super HttpObject> subscriber = 
                (Subscriber<? super HttpObject>)args[0];
            
            subscriber.onError(REQUEST_EXPIRED);
        }
    };
    
    private final class WorkHandler extends SimpleChannelInboundHandler<HttpObject> 
        implements Ordered {
        @Override
        public int ordinal() {
            return InboundFeature.LAST_FEATURE.ordinal() + 1;
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.warn("exceptionCaught {}, detail:{}", 
                    ctx.channel(), ExceptionUtils.exception2detail(cause));
            _requestReceiver.acceptEvent(ON_REQUEST_ERROR, cause);
            ctx.close();
        }

//        @Override
//        public void channelReadComplete(ChannelHandlerContext ctx) {
//            ctx.flush();
//        }
        
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            _requestReceiver.acceptEvent(ON_REQUEST_ERROR, new RuntimeException("channelInactive"));
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg)
                throws Exception {
            if (msg instanceof HttpRequest) {
                _isKeepAlive = HttpHeaders.isKeepAlive((HttpRequest)msg);
            }
            _requestReceiver.acceptEvent(ONHTTPOBJ_EVENT, msg);
        }

//        @Override
//        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
//        }
    }
    
    private final class CLS_REQ_ACTIVED extends BizStep 
        implements FlowLifecycleListener {
        private volatile boolean _isReqFully = false;
        private final List<HttpObject> _reqHttpObjects = new ArrayList<>();
        private final List<Subscriber<? super HttpObject>> _reqSubscribers = new ArrayList<>();
        
        CLS_REQ_ACTIVED() {
            super("httptrade.REQ_ACTIVED");
        }
        
        @Override
        public void afterEventReceiverCreated(final EventReceiver receiver)
                throws Exception {
        }
        
        @Override
        public void afterFlowDestroy() throws Exception {
            // release all HttpObjects of request
            for (HttpObject obj : this._reqHttpObjects) {
                ReferenceCountUtil.release(obj);
            }
            this._reqHttpObjects.clear();
            //  clear all request subscribers
            this._reqSubscribers.clear();
            detachAll();
        }
            
        private void callOnCompletedWhenFully(
                final Subscriber<? super HttpObject> subscriber) {
            if (this._isReqFully) {
                subscriber.onCompleted();
            }
        }
        
        @OnEvent(event = ADD_SUBSCRIBER)
        private BizStep doRegisterSubscriber(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                this._reqSubscribers.add(subscriber);
                for (HttpObject obj : this._reqHttpObjects) {
                    subscriber.onNext(obj);
                }
                callOnCompletedWhenFully(subscriber);
            }
            
            return BizStep.CURRENT_BIZSTEP;
        }

        @OnEvent(event = ON_HTTP_OBJECT)
        private BizStep doCacheHttpObject(final HttpObject httpObj) {
            if ( (httpObj instanceof FullHttpRequest) 
                || (httpObj instanceof LastHttpContent)) {
                this._isReqFully = true;
            }
            this._reqHttpObjects.add(ReferenceCountUtil.retain(httpObj));
            for (Subscriber<? super HttpObject> subscriber : this._reqSubscribers) {
                subscriber.onNext(httpObj);
                callOnCompletedWhenFully(subscriber);
            }
            
            return BizStep.CURRENT_BIZSTEP;
        }
        
        @OnEvent(event = ON_REQUEST_ERROR)
        private BizStep notifyRequestErrorAndEndFlow(final Throwable cause) {
            if ( !this._isReqFully ) {
                for (Subscriber<? super HttpObject> subscriber : this._reqSubscribers) {
                    subscriber.onError(cause);
                }
            }
            return null;
        }
        
        FullHttpRequest retainFullHttpRequest() {
            if (!this._isReqFully) {
                return null;
            }
            if (this._reqHttpObjects.size()>0) {
                if (this._reqHttpObjects.get(0) instanceof FullHttpRequest) {
                    return ((FullHttpRequest)this._reqHttpObjects.get(0)).retain();
                }
                
                final HttpRequest req = (HttpRequest)this._reqHttpObjects.get(0);
                final ByteBuf[] bufs = new ByteBuf[this._reqHttpObjects.size()-1];
                for (int idx = 1; idx<this._reqHttpObjects.size(); idx++) {
                    bufs[idx-1] = ((HttpContent)this._reqHttpObjects.get(idx)).content().retain();
                }
                return new DefaultFullHttpRequest(
                        req.getProtocolVersion(), 
                        req.getMethod(), 
                        req.getUri(), 
                        Unpooled.wrappedBuffer(bufs));
            } else {
                return null;
            }
        }
    }
    
    private final CLS_REQ_ACTIVED REQ_ACTIVED = (CLS_REQ_ACTIVED)new CLS_REQ_ACTIVED().freeze();
            
    private static final String SET_RESPONSE = "setResponse";
    private static final String ON_RESPONSE_NEXT = "onResponseNext";
    private static final String ON_RESPONSE_COMPLETED = "onResponseCompleted";
    private static final String ON_RESPONSE_ERROR = "onResponseError";
    
    private final class RESP_DETACHABLE {
        private final Subscription _mySubscription;
        
        RESP_DETACHABLE(final Subscription subscription) {
            this._mySubscription = subscription;
        }
        
        @OnEvent(event = "detach") 
        private BizStep detach() {
            if (null!=this._mySubscription) {
                this._mySubscription.unsubscribe();
            }
            return null;
        }
    }
    
    private final class ON_RESPONSE {
        private final BizStep _onNextStep;
        
        ON_RESPONSE(final BizStep onNextStep) {
            this._onNextStep = onNextStep;
        }
        
        @OnEvent(event = ON_RESPONSE_NEXT)
        private BizStep onNext(final HttpObject msg) {
            final Channel channel = _channelRef.get();
            if (null!=channel) {
                channel.write(ReferenceCountUtil.retain(msg));
                //  TODO check write future's isSuccess
            }
            return this._onNextStep;
        }
        
        @OnEvent(event = ON_RESPONSE_COMPLETED)
        private BizStep onCompleted() {
            try {
                _closure.close();
            } catch (IOException e) {
            }
            //  TODO disable continue call response
            _channelRecycler.onResponseCompleted(
                    detachChannel(), 
                    _isKeepAlive);
            return null;
        }
        
        @OnEvent(event = ON_RESPONSE_ERROR)
        private BizStep onError(final Throwable e) {
            LOG.warn("channel:{} 's response onError:{}", 
                    _channelRef.get(), ExceptionUtils.exception2detail(e));
            return null;
        }
    };
    
    private final class WAIT_RESP extends BizStep {
        private String genSuffix(final int idx) {
            return "." + idx;
        }
        
        private final int _idx;
        private final Subscription _lastSubscription;
        
        public WAIT_RESP(final int idx, final Subscription subscription) {
            super(("httptrade.WAIT_RESP." + idx));
            this._idx = idx;
            this._lastSubscription = subscription;
        }

        @OnEvent(event = SET_RESPONSE)
        private BizStep setResponse(final Observable<HttpObject> response) {
            //  unsubscribe current subscribe for response
            if (null!=this._lastSubscription) {
                this._lastSubscription.unsubscribe();
            }
            final String suffix = genSuffix(this._idx);
            final Subscription subscription = response.subscribe(
                RxSubscribers.guardUnsubscribed(
                    new Subscriber<HttpObject>() {
                    @Override
                    public void onCompleted() {
                        _responseReceiver.acceptEvent(ON_RESPONSE_COMPLETED + suffix);
                    }
                    @Override
                    public void onError(final Throwable e) {
                        _responseReceiver.acceptEvent(ON_RESPONSE_ERROR + suffix, e);
                    }
                    @Override
                    public void onNext(final HttpObject msg) {
                        _responseReceiver.acceptEvent(
                            new PairedGuardEventable(
                                Nettys._NETTY_REFCOUNTED_GUARD, ON_RESPONSE_NEXT + suffix), 
                            msg);
                    }})
                );
            
            return new WAIT_RESP(this._idx+1, subscription)
                .handler(buildOnResponse(
                            new BizStep("httptrade.LOCK_RESP" + suffix)
                            .handler(buildOnResponse(BizStep.CURRENT_BIZSTEP, suffix))
                            .handler(DefaultInvoker.invokers(new RESP_DETACHABLE(subscription)))
                            .freeze(), 
                        suffix))
                .handler(DefaultInvoker.invokers(new RESP_DETACHABLE(subscription)))
                .freeze();
        }
        
        private EventInvoker[] buildOnResponse(final BizStep onNextStep, final String suffix) {
            return DefaultInvoker.invokers(new ON_RESPONSE(onNextStep), suffix);
        }
    }
}
