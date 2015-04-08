/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.http.server.HttpTrade;
import org.jocean.http.server.InboundFeature;
import org.jocean.http.server.impl.DefaultHttpServer.ChannelRecycler;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

/**
 * @author isdom
 *
 */
public class DefaultHttpTrade implements HttpTrade {
    private static final Throwable REQUEST_EXPIRED = 
            new RuntimeException("request expired");
    private static final String ADD_SUBSCRIBER = "addSubscriber";
    private static final String ON_HTTP_OBJECT = "onHttpObject";
    private static final String ON_CHANNEL_ERROR = "onChannelError";
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
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
    
    private final Channel _channel;
    private final HandlersClosure _closure;
    private final EventReceiver _receiver;
    private volatile boolean _isKeepAlive = false;
    private final ChannelRecycler _channelRecycler;
    
    public DefaultHttpTrade(
            final Channel channel, 
            final EventEngine engine,
            final ChannelRecycler channelRecycler) {
        this._channelRecycler = channelRecycler;
        this._channel = channel;
        this._closure = Nettys.channelHandlersClosure(this._channel);
        this._channel.pipeline().addLast(
                "work", this._closure.call(new WorkHandler()));
        this._receiver = engine.create(this.toString(), this.ACTIVED);
    }
    
    @Override
    public void close() throws IOException {
        this._channel.close();
    }
    
    @Override
    public void response(final Observable<HttpObject> response) {
        response.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onCompleted() {
                try {
                    _closure.close();
                } catch (IOException e) {
                }
                //  TODO disable continue call response
                _channelRecycler.onResponseCompleted(_channel, _isKeepAlive);
            }

            @Override
            public void onError(final Throwable e) {
                LOG.warn("channel:{} 's response onError:{}", 
                        _channel, ExceptionUtils.exception2detail(e));
            }

            @Override
            public void onNext(final HttpObject msg) {
                _channel.write(ReferenceCountUtil.retain(msg));
                //  TODO check write future's isSuccess
            }});
    }
    
    @Override
    public Observable<HttpObject> request() {
        return Observable.create(new OnSubscribeRequest());
    }

    private class OnSubscribeRequest implements OnSubscribe<HttpObject> {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            if (!subscriber.isUnsubscribed()) {
                if (_channel.isActive()) {
                    _receiver.acceptEvent(ADDSUBSCRIBER_EVENT, subscriber);
                } else {
                    subscriber.onError(REQUEST_EXPIRED);
                }
            }
        }
    }

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
            _receiver.acceptEvent(ON_CHANNEL_ERROR, cause);
            ctx.close();
        }

//        @Override
//        public void channelReadComplete(ChannelHandlerContext ctx) {
//            ctx.flush();
//        }
        
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            _receiver.acceptEvent(ON_CHANNEL_ERROR, new RuntimeException("channelInactive"));
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg)
                throws Exception {
            if (msg instanceof HttpRequest) {
                _isKeepAlive = HttpHeaders.isKeepAlive((HttpRequest)msg);
            }
            _receiver.acceptEvent(ONHTTPOBJ_EVENT, msg);
        }

//        @Override
//        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
//        }
    }
    
    private final BizStep ACTIVED = new BizStep("httpinbound.ACTIVED") {
            private boolean _isFully = false;
            private final List<HttpObject> _httpObjects = new ArrayList<>();
            private final List<Subscriber<? super HttpObject>> _subscribers = new ArrayList<>();
            
            private void callOnCompletedWhenFully(
                    final Subscriber<? super HttpObject> subscriber) {
                if (this._isFully) {
                    subscriber.onCompleted();
                }
            }
            
            @OnEvent(event = ADD_SUBSCRIBER)
            private BizStep doRegisterSubscriber(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    this._subscribers.add(subscriber);
                    for (HttpObject obj : this._httpObjects) {
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
                    this._isFully = true;
                }
                this._httpObjects.add(ReferenceCountUtil.retain(httpObj));
                for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
                    subscriber.onNext(httpObj);
                    callOnCompletedWhenFully(subscriber);
                }
                
                return BizStep.CURRENT_BIZSTEP;
            }
            
            @OnEvent(event = ON_CHANNEL_ERROR)
            private BizStep notifyChannelErrorAndEndFlow(final Throwable cause) {
                if ( !this._isFully ) {
                    for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
                        subscriber.onError(cause);
                    }
                }
                
                // release all HttpObjects
                for (HttpObject obj : this._httpObjects) {
                    ReferenceCountUtil.release(obj);
                }
                this._httpObjects.clear();
                
                return null;
            }
        }
        .freeze();
}
