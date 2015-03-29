/**
 * 
 */
package org.jocean.http.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
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
import org.jocean.event.api.internal.DefaultInvoker;
import org.jocean.http.HttpFeature;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

/**
 * @author isdom
 *
 */
public class HttpInbound {
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
            LoggerFactory.getLogger(HttpInbound.class);
    
    final BizStep PROCESS = new BizStep("httpinbound.PROCESS")
        .handler(DefaultInvoker.invokers(this))
        .freeze();
    
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
            
            subscriber.onError(new RuntimeException("request expired"));
        }
    };
    
    public HttpInbound(
            final Channel channel, 
            final EventEngine engine,
            final SslContext sslCtx, 
            final HttpFeature... features) {
        this._channel = channel;
        initChannel(sslCtx, Features.featuresAsInt(features));
        this._receiver = engine.create(this.toString(), PROCESS);
    }
    
    public Observable<HttpObject> request() {
        return Observable.create(new OnSubscribeRequest());
    }

    private class OnSubscribeRequest implements OnSubscribe<HttpObject> {
        @Override
        public void call(final Subscriber<? super HttpObject> subscriber) {
            try {
                _receiver.acceptEvent(ADDSUBSCRIBER_EVENT, subscriber);
            } catch (Exception e) {
            }
        }
    }

    private void callOnCompleteWhenFully(
            final Subscriber<? super HttpObject> subscriber) {
        if (this._isFully) {
            subscriber.onCompleted();
        }
    }
    
    @OnEvent(event = ADD_SUBSCRIBER)
    private BizStep doRegisterSubscriber(final Subscriber<? super HttpObject> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._subscribers.add(subscriber);
            for (HttpObject obj : this._httpObjs) {
                subscriber.onNext(obj);
            }
            callOnCompleteWhenFully(subscriber);
        }
        
        return BizStep.CURRENT_BIZSTEP;
    }

    @OnEvent(event = ON_HTTP_OBJECT)
    private BizStep doCacheHttpObject(final HttpObject httpObj) {
        if ( (httpObj instanceof FullHttpRequest) 
            || (httpObj instanceof LastHttpContent)) {
            this._isFully = true;
        }
        this._httpObjs.add(ReferenceCountUtil.retain(httpObj));
        for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
            subscriber.onNext(httpObj);
            callOnCompleteWhenFully(subscriber);
        }
        
        return BizStep.CURRENT_BIZSTEP;
    }
    
    @OnEvent(event = ON_CHANNEL_ERROR)
    private BizStep notifyInactiveAndEndFlow(final Throwable cause) {
        if ( !this._isFully ) {
            for (Subscriber<? super HttpObject> subscriber : this._subscribers) {
                subscriber.onError(cause);
            }
        }
        
        return null;
    }
    
    
    private void initChannel(final SslContext sslCtx, final int featuresAsInt) {
        final ChannelPipeline pipeline = this._channel.pipeline();
        
        if (Features.isEnabled(featuresAsInt, HttpFeature.EnableLOG)) {
            //  add first
            pipeline.addFirst("log", new LoggingHandler());
        }
        
        if (Features.isEnabled(featuresAsInt, HttpFeature.CloseOnIdle)) {
            pipeline.addLast("closeOnIdle", new IdleStateHandler(0, 0, 180) {
                @Override
                protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("channelIdle:{} , close channel[{}]", evt.state().name(), ctx.channel());
                    }
                    _channel.close();
                }
            });
        }
        
        if (Features.isEnabled(featuresAsInt, HttpFeature.EnableSSL)) {
            pipeline.addLast(sslCtx.newHandler(this._channel.alloc()));
        }
        
        pipeline.addLast(new HttpServerCodec());
        
        if (HttpFeature.isCompressEnabled(featuresAsInt)) {
            //  add last
            pipeline.addLast("deflater", new HttpContentCompressor());
        }
        
        pipeline.addLast(new WorkHandler());
    }
    
    private final class WorkHandler extends ChannelInboundHandlerAdapter{
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.info("exceptionCaught {}, detail:{}", 
                    ctx.channel(), ExceptionUtils.exception2detail(cause));
            _receiver.acceptEvent(ON_CHANNEL_ERROR, cause);
            ctx.close();
        }

//        @Override
//        public void channelReadComplete(ChannelHandlerContext ctx) {
//            ctx.flush();
//        }
        
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            try {
                if (msg instanceof HttpObject) {
                    _receiver.acceptEvent(ONHTTPOBJ_EVENT, msg);
                }
                else {
                    LOG.warn("messageReceived:{} unhandled msg [{}]",ctx.channel(),msg);
                    return;
                }
            }
            finally {
                ReferenceCountUtil.release(msg);
            }
        }
        
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            _receiver.acceptEvent(ON_CHANNEL_ERROR, new RuntimeException("channelInactive"));
        }

//        @Override
//        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
//        }
    }
    
    private final Channel _channel;
    private final EventReceiver _receiver;
    private final List<HttpObject> _httpObjs = new ArrayList<>();
    private boolean _isFully = false;
    private final List<Subscriber<? super HttpObject>> _subscribers = new ArrayList<>();
}
