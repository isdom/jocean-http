/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.util.ArrayList;
import java.util.List;

import org.jocean.event.api.AbstractUnhandleAware;
import org.jocean.event.api.BizStep;
import org.jocean.event.api.EventEngine;
import org.jocean.event.api.EventReceiver;
import org.jocean.event.api.PairedGuardEventable;
import org.jocean.event.api.annotation.OnEvent;
import org.jocean.http.server.HttpInbound;
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
public class DefaultHttpInbound implements HttpInbound {
    private static final Throwable REQUEST_EXPIRED = 
            new RuntimeException("request expired");
    private static final String ADD_SUBSCRIBER = "addSubscriber";
    private static final String ON_HTTP_OBJECT = "onHttpObject";
    private static final String ON_CHANNEL_ERROR = "onChannelError";
    
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpInbound.class);
    
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
    private final EventReceiver _receiver;
    
    public DefaultHttpInbound(
            final Channel channel, 
            final EventEngine engine) {
        this._channel = channel;
        initChannel();
        this._receiver = engine.create(this.toString(), this.ACTIVED);
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
                    try {
                        _receiver.acceptEvent(ADDSUBSCRIBER_EVENT, subscriber);
                    } catch (Exception e) {
                    }
                } else {
                    subscriber.onError(REQUEST_EXPIRED);
                }
            }
        }
    }

    private void initChannel() {
        final ChannelPipeline pipeline = this._channel.pipeline();
        
        Nettys.insertHandler(
                pipeline,
                InboundFeature.HTTPSERVER_CODEC.name(), 
                new HttpServerCodec(),
                InboundFeature.TO_ORDINAL);
        pipeline.addLast("work", new WorkHandler());
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
