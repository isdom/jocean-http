package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Iterator;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.Feature.HandlerBuilder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
import org.jocean.idiom.rx.RxObservables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Actions;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(RxNettys.class);
    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static void applyFeaturesToChannel(
            final Channel channel,
            final HandlerBuilder builder,
            final Feature[] features,
            final Action1<Action0> onTerminate) {
        for (Feature feature : features) {
            if (feature instanceof FeatureOverChannelHandler) {
                final ChannelHandler handler = ((FeatureOverChannelHandler)feature).call(builder, channel.pipeline());
                if (null != handler && null!=onTerminate) {
                    onTerminate.call(
                        RxNettys.actionToRemoveHandler(channel, handler));
                }
            }
        }
    }
    
    public static Action0 actionToRemoveHandler(
        final Channel channel,
        final ChannelHandler handler) {
        return null != handler 
                ? new Action0() {
                    @Override
                    public void call() {
                        final ChannelPipeline pipeline = channel.pipeline();
                        final ChannelHandlerContext ctx = pipeline.context(handler);
                        if (ctx != null) {
                            pipeline.remove(handler);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("actionToRemoveHandler: channel ({}) remove handler({}/{}) success.", 
                                        channel, ctx.name(), handler);
                            }
                        }
                    }}
                : Actions.empty();
    }
    
    public static final Func1<Object, Object> RETAIN_OBJ = 
            new Func1<Object, Object>() {
        @Override
        public Object call(final Object obj) {
            //    retain obj for blocking
            return ReferenceCountUtil.retain(obj);
        }};
        
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <T> Func1<T, T> retainer() {
        return (Func1)RETAIN_OBJ;
    }

    public static <T, V> Observable<T> observableFromFuture(final Future<V> future) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<T>() {
            @Override
            public void call(final Subscriber<? super T> subscriber) {
                future.addListener(new GenericFutureListener<Future<V>>() {
                    @Override
                    public void operationComplete(final Future<V> f)
                            throws Exception {
                        if (!subscriber.isUnsubscribed()) {
                            if (f.isSuccess()) {
                                subscriber.onCompleted();
                            } else {
                                subscriber.onError(f.cause());
                            }
                        }
                    }
                });
            }});
    }
    
    public static Observable<? extends Channel> channelObservableFromFuture(final ChannelFuture future) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                future.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(final ChannelFuture f)
                            throws Exception {
                        if (!subscriber.isUnsubscribed()) {
                            if (f.isSuccess()) {
                                subscriber.onNext(f.channel());
                                subscriber.onCompleted();
                            } else {
                                subscriber.onError(f.cause());
                            }
                        }
                    }
                });
            }});
    }
    
    public static Func1<Channel, Observable<? extends Channel>> asyncConnectTo(
            final SocketAddress remoteAddress) {
        return new Func1<Channel, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return Observable.unsafeCreate(new Observable.OnSubscribe<ChannelFuture>() {
                    @Override
                    public void call(final Subscriber<? super ChannelFuture> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final ChannelFuture future = channel.connect(remoteAddress);
                            //  TODO doOnUnsubscribe
//                            RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
                            subscriber.onNext(future);
                            subscriber.onCompleted();
                        }
                    }}).compose(channelFutureToChannel());
            }};
    }
    
    public static Action1<Channel> actionPermanentlyApplyFeatures(
            final HandlerBuilder builder,
            final Feature[] features) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                applyFeaturesToChannel(
                        channel, 
                        builder, 
                        features, 
                        null);
            }};
    }
    
    public static Single.Transformer<? super Channel, ? extends Channel> markAndPushChannelWhenReadyAsSingle(final boolean isSSLEnabled) {
        return new Single.Transformer<Channel, Channel>() {
            @Override
            public Single<Channel> call(final Single<Channel> source) {
                if (isSSLEnabled) {
                    return source.flatMap(new Func1<Channel, Single<? extends Channel>> () {
                        @Override
                        public Single<? extends Channel> call(final Channel channel) {
                            return Single.create(new Single.OnSubscribe<Channel>() {
                                @Override
                                public void call(final SingleSubscriber<? super Channel> subscriber) {
                                    if (!subscriber.isUnsubscribed()) {
                                        APPLY.SSLNOTIFY.applyTo(channel.pipeline(), 
                                            new Action1<Channel>() {
                                                @Override
                                                public void call(final Channel ch) {
                                                    Nettys.setChannelReady(ch);
                                                    subscriber.onSuccess(ch);
                                                    if (LOG.isDebugEnabled()) {
                                                        LOG.debug("channel({}): userEventTriggered for ssl handshake success", ch);
                                                    }
                                                }},
                                            new Action1<Throwable>() {
                                                @Override
                                                public void call(final Throwable e) {
                                                    subscriber.onError(e);
                                                    LOG.warn("channel({}): userEventTriggered for ssl handshake failure:{}",
                                                            channel,
                                                            ExceptionUtils.exception2detail(e));
                                                }});
                                    } else {
                                        LOG.warn("SslHandshakeNotifier: channelSubscriber {} has unsubscribe", subscriber);
                                    }
                                }});
                        }});
                } else {
                    return source.doOnSuccess(new Action1<Channel>() {
                        @Override
                        public void call(final Channel channel) {
                            Nettys.setChannelReady(channel);
                        }});
                }
            }};
    }
    
    public static Observable.Transformer<? super Channel, ? extends Channel> markAndPushChannelWhenReady(final boolean isSSLEnabled) {
        return new Observable.Transformer<Channel, Channel>() {
            @Override
            public Observable<Channel> call(final Observable<Channel> source) {
                if (isSSLEnabled) {
                    return source.flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                        @Override
                        public Observable<? extends Channel> call(final Channel channel) {
                            return Observable.create(new Observable.OnSubscribe<Channel>() {
                                @Override
                                public void call(final Subscriber<? super Channel> subscriber) {
                                    if (!subscriber.isUnsubscribed()) {
                                        APPLY.SSLNOTIFY.applyTo(channel.pipeline(), 
                                            new Action1<Channel>() {
                                                @Override
                                                public void call(final Channel ch) {
                                                    Nettys.setChannelReady(ch);
                                                    subscriber.onNext(ch);
                                                    subscriber.onCompleted();
                                                    if (LOG.isDebugEnabled()) {
                                                        LOG.debug("channel({}): userEventTriggered for ssl handshake success", ch);
                                                    }
                                                }},
                                            new Action1<Throwable>() {
                                                @Override
                                                public void call(final Throwable e) {
                                                    subscriber.onError(e);
                                                    LOG.warn("channel({}): userEventTriggered for ssl handshake failure:{}",
                                                            channel,
                                                            ExceptionUtils.exception2detail(e));
                                                }});
                                    } else {
                                        LOG.warn("SslHandshakeNotifier: channelSubscriber {} has unsubscribe", subscriber);
                                    }
                                }});
                        }});
                } else {
                    return source.doOnNext(new Action1<Channel>() {
                        @Override
                        public void call(final Channel channel) {
                            Nettys.setChannelReady(channel);
                        }});
                }
            }};
    }
    
    public static Subscription subscriptionForCloseChannel(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                channel.close();
            }});
    }
    
    public static byte[] httpObjectsAsBytes(final Iterator<HttpObject> itr)
            throws IOException {
        final CompositeByteBuf composite = Unpooled.compositeBuffer();
        try {
            while (itr.hasNext()) {
                final HttpObject obj = itr.next();
                if (obj instanceof HttpContent) {
                    composite.addComponent(((HttpContent)obj).content());
                }
            }
            composite.setIndex(0, composite.capacity());
            
            @SuppressWarnings("resource")
            final InputStream is = new ByteBufInputStream(composite);
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return bytes;
        } finally {
            ReferenceCountUtil.release(composite);
        }
    }
    
    public static <T> void releaseObjects(final Collection<T> objs) {
        synchronized (objs) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("start to releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
                for (T obj : objs) {
                    try {
                        if (ReferenceCountUtil.release(obj)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("({}) released and deallocated success.", obj); 
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                if ( obj instanceof ReferenceCounted) {
                                    LOG.debug("({}) released BUT refcnt == {} > 0.", obj, ((ReferenceCounted)obj).refCnt()); 
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when ReferenceCountUtil.release {}, detail: {}",
                                obj, ExceptionUtils.exception2detail(e));
                    }
                }
            } finally {
                objs.clear();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("end of releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
            }
        }
    }
    
    public static <E, T> Observable.Transformer<? super T, ? extends T> releaseAtLast(final Collection<E> objs) {
        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(final Observable<T> source) {
                return source.doAfterTerminate(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("finallyDo: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }})
                    .doOnUnsubscribe(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("doOnUnsubscribe: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }});
            }};
    }
    
    public static Observable<HttpObject> response401Unauthorized(
            final HttpVersion version, final String vlaueOfWWWAuthenticate) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, vlaueOfWWWAuthenticate);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static Observable<HttpObject> response200OK(
            final HttpVersion version) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static Func1<HttpObject[], FullHttpRequest> BUILD_FULL_REQUEST = new Func1<HttpObject[], FullHttpRequest>() {
        @Override
        public FullHttpRequest call(final HttpObject[] httpobjs) {
            if (LOG.isDebugEnabled()) {
                int idx = 0;
                for (HttpObject httpobj : httpobjs) {
                    LOG.debug("BUILD_FULL_REQUEST: dump [{}] httpobj {}", ++idx, httpobj);
                }
            }
            
            if (httpobjs.length>0 
            && (httpobjs[0] instanceof HttpRequest) 
            && (httpobjs[httpobjs.length-1] instanceof LastHttpContent)) {
                if (httpobjs[0] instanceof FullHttpRequest) {
                    return ((FullHttpRequest)httpobjs[0]).retainedDuplicate();
                }
                
                final HttpRequest req = (HttpRequest)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                        req.protocolVersion(), 
                        req.method(), 
                        req.uri(), 
                        Unpooled.wrappedBuffer(bufs));
                fullreq.headers().add(req.headers());
                //  ? need update Content-Length header field ?
                return fullreq;
            } else {
                return null;
            }
        }};
    public static Func1<HttpObject[], FullHttpResponse> BUILD_FULL_RESPONSE = new Func1<HttpObject[], FullHttpResponse>() {
        @Override
        public FullHttpResponse call(final HttpObject[] httpobjs) {
            if (httpobjs.length>0 
            && (httpobjs[0] instanceof HttpResponse) 
            && (httpobjs[httpobjs.length-1] instanceof LastHttpContent)) {
                if (httpobjs[0] instanceof FullHttpResponse) {
                    return ((FullHttpResponse)httpobjs[0]).retainedDuplicate();
                }
                
                final HttpResponse resp = (HttpResponse)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpResponse fullresp = new DefaultFullHttpResponse(
                        resp.protocolVersion(), 
                        resp.status(),
                        Unpooled.wrappedBuffer(bufs));
                fullresp.headers().add(resp.headers());
                //  ? need update Content-Length header field ?
                return fullresp;
            } else {
                return null;
            }
        }};
        
    public static Func1<HttpObject, Observable<? extends HttpObject>> splitFullHttpMessage() {
        return SPLIT_FULLHTTPMSG;
    }

    private final static Func1<HttpObject, Observable<? extends HttpObject>> SPLIT_FULLHTTPMSG = 
            new Func1<HttpObject, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final HttpObject obj) {
                if (obj instanceof FullHttpRequest) {
                    return Observable.just(requestOf((HttpRequest)obj), lastContentOf((FullHttpMessage)obj));
                } else if (obj instanceof FullHttpResponse) {
                    return Observable.just(responseOf((HttpResponse)obj), lastContentOf((FullHttpMessage)obj));
                } else {
                    return Observable.just(obj);
                }
            }};
            
    private static HttpRequest requestOf(final HttpRequest req) {
        return new ProxyBuilder<>(HttpRequest.class, req).buildProxy();
    }
    
    private static HttpResponse responseOf(final HttpResponse resp) {
        return new ProxyBuilder<>(HttpResponse.class, resp).buildProxy();
    }
    
    private static LastHttpContent lastContentOf(final FullHttpMessage msg) {
        return new ProxyBuilder<>(LastHttpContent.class, msg).buildProxy();
    }

    //  对 HttpMessage 中的 HttpContent 产生独立的 readIndex & writeIndex
    public static Observable.Transformer<? super HttpObject, ? extends HttpObject> duplicateHttpContent() {
        return new Observable.Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> source) {
                return source.map(new Func1<HttpObject, HttpObject>() {
                    @Override
                    public HttpObject call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpContent) {
                            return ((HttpContent)httpobj).duplicate();
                        } else {
                            return httpobj;
                        }
                    }});
            }};
    }

    private final static Observable.Transformer<HttpObject, HttpRequest> AS_HTTPREQ = 
        new Observable.Transformer<HttpObject, HttpRequest>() {
            @Override
            public Observable<HttpRequest> call(final Observable<HttpObject> httpMessage) {
                return httpMessage.first().map(new Func1<HttpObject, HttpRequest>() {
                    @Override
                    public HttpRequest call(final HttpObject httpobj) {
                        if (httpobj instanceof HttpRequest) {
                            return (HttpRequest)httpobj;
                        } else {
                            return null;
                        }
                    }});
            }};
        
    public static Observable.Transformer<? super HttpObject, ? extends HttpRequest> asHttpRequest() {
        return AS_HTTPREQ;
    }
    
    private final static Observable.Transformer<HttpObject, HttpResponse> AS_HTTPRESP = 
            new Observable.Transformer<HttpObject, HttpResponse>() {
                @Override
                public Observable<HttpResponse> call(final Observable<HttpObject> httpMessage) {
                    return httpMessage.first().map(new Func1<HttpObject, HttpResponse>() {
                        @Override
                        public HttpResponse call(final HttpObject httpobj) {
                            if (httpobj instanceof HttpResponse) {
                                return (HttpResponse)httpobj;
                            } else {
                                return null;
                            }
                        }});
                }};
            
    public static Observable.Transformer<? super HttpObject, ? extends HttpResponse> asHttpResponse() {
        return AS_HTTPRESP;
    }
        
    private final static Observable.Transformer<ChannelFuture, Channel> CHANNELFUTURE_CHANNEL = 
            new Observable.Transformer<ChannelFuture, Channel>() {
                @Override
                public Observable<Channel> call(final Observable<ChannelFuture> source) {
                    return source.flatMap(new Func1<ChannelFuture, Observable<? extends Channel>>() {
                        @Override
                        public Observable<? extends Channel> call(
                                final ChannelFuture f) {
                            return channelObservableFromFuture(f);
                        }});
                }};
                
    public static Observable.Transformer<ChannelFuture, Channel> channelFutureToChannel() {
        return CHANNELFUTURE_CHANNEL;
    }
    
    public static Observable<? extends HttpObject> inboundFromChannel(
        final Channel channel,
        final Action1<Action0> onTerminate) {
        return Observable.create(new Observable.OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    if (null != channel.pipeline().get(APPLY.HTTPOBJ_SUBSCRIBER.name()) ) {
                        // already add HTTPOBJ_SUBSCRIBER Handler, so throw exception
                        LOG.warn("channel ({}) already add HTTPOBJ_SUBSCRIBER handler, internal error",
                                channel);
                        throw new RuntimeException("Channel[" + channel + "]already add HTTPOBJ_SUBSCRIBER handler.");
                    }
                    onTerminate.call(
                        RxNettys.actionToRemoveHandler(channel, 
                        APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber)));
                } else {
                    LOG.warn("subscriber {} isUnsubscribed, can't used as HTTPOBJ_SUBSCRIBER ", subscriber);
                }
            }} )
            .compose(RxObservables.<HttpObject>ensureSubscribeAtmostOnce());
    }
    
    @SuppressWarnings("unchecked")
    public static <T extends ChannelHandler> T applyToChannelWithUninstall(
            final Channel channel, 
            final Action1<Action0> onTerminate,
            final APPLY apply, 
            final Object... args) {
        final ChannelHandler handler = 
            apply.applyTo(channel.pipeline(), args);
        
        onTerminate.call(RxNettys.actionToRemoveHandler(channel, handler));
        return (T)handler;
    }
}
