package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Iterator;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
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
import rx.functions.Action2;
import rx.functions.Actions;
import rx.functions.Func1;
import rx.internal.util.SubscriptionList;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(RxNettys.class);
    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static class DefaultDoOnUnsubscribe implements DoOnUnsubscribe, Subscription {

        @Override
        public void call(Subscription s) {
            this._subscriptionList.add(s);
        }
        
        @Override
        public void unsubscribe() {
            this._subscriptionList.unsubscribe();
        }
        
        @Override
        public boolean isUnsubscribed() {
            return this._subscriptionList.isUnsubscribed();
        }
        
        final private SubscriptionList _subscriptionList = new SubscriptionList();
    }
    
    public static DefaultDoOnUnsubscribe createDoOnUnsubscribe() {
        return new DefaultDoOnUnsubscribe();
    }
    
    public static void applyFeaturesToChannel(
            final Channel channel,
            final HandlerBuilder builder,
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        for (Feature feature : features) {
            if (feature instanceof FeatureOverChannelHandler) {
                final ChannelHandler handler = ((FeatureOverChannelHandler)feature).call(builder, channel.pipeline());
                if (null != handler && null!=doOnUnsubscribe) {
                    doOnUnsubscribe.call(
                        Subscriptions.create(
                            RxNettys.actionToRemoveHandler(channel, handler)));
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
        
    public static <V> GenericFutureListener<Future<V>> listenerOfOnError(final Subscriber<?> subscriber) {
        return new GenericFutureListener<Future<V>>() {
            @Override
            public void operationComplete(final Future<V> f)
                    throws Exception {
                if (!f.isSuccess() && !subscriber.isUnsubscribed()) {
                    subscriber.onError(f.cause());
                }
            }
        };
    }
    
    public static ChannelFutureListener listenerOfOnNextAndCompleted(final Subscriber<? super Channel> subscriber) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f)
                    throws Exception {
                if (f.isSuccess() && !subscriber.isUnsubscribed()) {
                    subscriber.onNext(f.channel());
                    subscriber.onCompleted();
                }
            }
        };
    }
    
    public static ChannelFutureListener listenerOfSetServerChannel(
            final ServerChannelAware serverChannelAware) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                if (future.isSuccess() && null!=serverChannelAware) {
                    try {
                        serverChannelAware.setServerChannel((ServerChannel)future.channel());
                    } catch (Exception e) {
                        LOG.warn("exception when invoke setServerChannel for channel ({}), detail: {}",
                                future.channel(), ExceptionUtils.exception2detail(e));
                    }
                }
            }};
    }
    
    public static Func1<ChannelFuture, Observable<? extends Channel>> funcFutureToChannel() {
        return new Func1<ChannelFuture, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final ChannelFuture future) {
                return Observable.create(new Observable.OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            future.addListener(listenerOfOnError(subscriber))
                                .addListener(listenerOfOnNextAndCompleted(subscriber));
                        }
                    }});
            }};
    }
    
    public static Func1<Channel, Single<? extends Channel>> asyncConnectToAsSingle(
            final SocketAddress remoteAddress,
            final DoOnUnsubscribe doOnUnsubscribe) {
        return new Func1<Channel, Single<? extends Channel>>() {
            @Override
            public Single<? extends Channel> call(final Channel channel) {
                return Single.create(new Single.OnSubscribe<Channel>() {
                    @Override
                    public void call(final SingleSubscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final ChannelFuture future = channel.connect(remoteAddress);
                            doOnUnsubscribe.call(Subscriptions.from(future));
                            future.addListener(listenerOfOnError(subscriber))
                                .addListener(listenerOfOnSuccess(subscriber));
                        }
                    }});
            }};
    }
    
    public static Func1<Channel, Observable<? extends Channel>> asyncConnectTo(
            final SocketAddress remoteAddress) {
        return new Func1<Channel, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return Observable.create(new Observable.OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final ChannelFuture future = channel.connect(remoteAddress);
                            RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
                            future.addListener(listenerOfOnError(subscriber))
                                .addListener(listenerOfOnNextAndCompleted(subscriber));
                        }
                    }});
            }};
    }
    
//    public static <M> Observable.Transformer<M, Channel> sendRequestThenPushChannel(final Channel channel) {
//        return new Observable.Transformer<M, Channel>() {
//            @Override
//            public void call(final Observable<M> request) {
//                return request.doOnCompleted(new Action0() {
//                          @Override
//                          public void call() {
//                              channel.flush();
//                          }})
//                      .doOnNext(new Action1<M>() {
//                          @Override
//                          public void call(final M msg) {
//                              final ChannelFuture future = channel.write(ReferenceCountUtil.retain(msg));
//                              RxNettys.doOnUnsubscribe(channel, Subscriptions.from(future));
//                          }
//                      })
//            };
//        };
//    }
    
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
    
    public static Action1<Channel> actionUndoableApplyFeatures(
            final HandlerBuilder builder,
            final Feature[] features) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                applyFeaturesToChannel(
                        channel, 
                        builder, 
                        features, 
                        queryDoOnUnsubscribe(channel));
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
    
    public static Func1<Channel, Observable<? extends HttpObject>> waitforHttpResponse(
            final Action2<Channel, Subscriber<?>> afterApplyHttpSubscriber) {
        return new Func1<Channel, Observable<? extends HttpObject>>() {
            @Override
            public Observable<? extends HttpObject> call(final Channel channel) {
                return Observable.create(new Observable.OnSubscribe<HttpObject>() {
                    @Override
                    public void call(final Subscriber<? super HttpObject> subscriber) {
                        RxNettys.doOnUnsubscribe(channel, 
                            Subscriptions.create(RxNettys.actionToRemoveHandler(channel, 
                                APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber))));
                        if (null != afterApplyHttpSubscriber) {
                            afterApplyHttpSubscriber.call(channel, subscriber);
                        }
                    }});
            };
        };
    }
    
    public static <V> GenericFutureListener<Future<V>> listenerOfOnError(final SingleSubscriber<?> subscriber) {
        return new GenericFutureListener<Future<V>>() {
            @Override
            public void operationComplete(final Future<V> f)
                    throws Exception {
                if (!f.isSuccess() && !subscriber.isUnsubscribed()) {
                    subscriber.onError(f.cause());
                }
            }
        };
    }
    
    public static ChannelFutureListener listenerOfOnSuccess(final SingleSubscriber<? super Channel> subscriber) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f)
                    throws Exception {
                if (f.isSuccess() && !subscriber.isUnsubscribed()) {
                    subscriber.onSuccess(f.channel());
                }
            }
        };
    }
    
    public static Func1<ChannelFuture, Single<? extends Channel>> singleFutureToChannel() {
        return new Func1<ChannelFuture, Single<? extends Channel>>() {
            @Override
            public Single<? extends Channel> call(final ChannelFuture future) {
                return Single.create(new Single.OnSubscribe<Channel>() {
                    @Override
                    public void call(final SingleSubscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            future.addListener(listenerOfOnError(subscriber));
                            future.addListener(listenerOfOnSuccess(subscriber));
                        }
                    }});
            }};
    }
    
    public static Subscription subscriptionForReleaseChannel(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                Nettys.releaseChannel(channel);
            }});
    }
    
    public static <T> Action1<T> enableReleaseChannelWhenUnsubscribe() {
        return new Action1<T>() {
            @Override
            public void call(final T channelOrFuture) {
                Channel ch = null;
                if (channelOrFuture instanceof Channel) {
                    ch = (Channel)channelOrFuture;
                } else if (channelOrFuture instanceof ChannelFuture) {
                    ch = ((ChannelFuture)channelOrFuture).channel();
                }
                if (null!=ch) {
                    final Channel channel = ch;
                    RxNettys.doOnUnsubscribe(ch,subscriptionForReleaseChannel(channel));
                }
            }};
    }
    
    private static final AttributeKey<DoOnUnsubscribe> DO_ON_UNSUBSCRIBE = AttributeKey.valueOf("__DO_ON_UNSUBSCRIBE");
    
    public static void installDoOnUnsubscribe(final Channel channel, final DoOnUnsubscribe doOnUnsubscribe) {
        channel.attr(DO_ON_UNSUBSCRIBE).set(doOnUnsubscribe);
    }
    
    public static DoOnUnsubscribe queryDoOnUnsubscribe(final Channel channel) {
        return channel.attr(DO_ON_UNSUBSCRIBE).get();
    }
    
    public static void doOnUnsubscribe(final Channel channel, final Subscription subscription) {
        final DoOnUnsubscribe doOnUnsubscribe = channel.attr(DO_ON_UNSUBSCRIBE).get();
        if (null!=doOnUnsubscribe) {
            try {
                doOnUnsubscribe.call(subscription);
            } catch (Exception e) {
                LOG.warn("exception when invoke doOnUnsubscribe {} for channel {}, detail: {}",
                        doOnUnsubscribe, channel, ExceptionUtils.exception2detail(e));
            }
        }
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
        HttpHeaders.setHeader(response, HttpHeaders.Names.WWW_AUTHENTICATE, vlaueOfWWWAuthenticate);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static Observable<HttpObject> response200OK(
            final HttpVersion version) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.OK);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static Observable<? extends HttpObject> httpobjObservable(final Channel channel) {
        return Observable.create(new Observable.OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.add(Subscriptions.create(
                        RxNettys.actionToRemoveHandler(channel, 
                            APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber))));
                }
            }} );
    }
    
    public static Func1<HttpObject[], FullHttpRequest> BUILD_FULL_REQUEST = new Func1<HttpObject[], FullHttpRequest>() {
        @Override
        public FullHttpRequest call(final HttpObject[] httpobjs) {
            if (httpobjs.length>0 
            && (httpobjs[0] instanceof HttpRequest) 
            && (httpobjs[httpobjs.length-1] instanceof LastHttpContent)) {
                if (httpobjs[0] instanceof FullHttpRequest) {
                    return ((FullHttpRequest)httpobjs[0]).retain();
                }
                
                final HttpRequest req = (HttpRequest)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpRequest fullreq = new DefaultFullHttpRequest(
                        req.getProtocolVersion(), 
                        req.getMethod(), 
                        req.getUri(), 
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
                    return ((FullHttpResponse)httpobjs[0]).retain();
                }
                
                final HttpResponse resp = (HttpResponse)httpobjs[0];
                final ByteBuf[] bufs = new ByteBuf[httpobjs.length-1];
                for (int idx = 1; idx<httpobjs.length; idx++) {
                    bufs[idx-1] = ((HttpContent)httpobjs[idx]).content().retain();
                }
                final DefaultFullHttpResponse fullresp = new DefaultFullHttpResponse(
                        resp.getProtocolVersion(), 
                        resp.getStatus(),
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
                    return Observable.just(requestOf((FullHttpRequest)obj), lastContentOf((FullHttpMessage)obj));
                } else if (obj instanceof FullHttpResponse) {
                    return Observable.just(responseOf((FullHttpResponse)obj), lastContentOf((FullHttpMessage)obj));
                } else {
                    return Observable.just(obj);
                }
            }};
            
    private static HttpRequest requestOf(final FullHttpRequest fullReq) {
        return new ProxyBuilder<>(HttpRequest.class, fullReq).buildProxy();
    }
    
    private static HttpResponse responseOf(final FullHttpResponse fullResp) {
        return new ProxyBuilder<>(HttpResponse.class, fullResp).buildProxy();
    }
    
    private static LastHttpContent lastContentOf(final FullHttpMessage msg) {
        return new ProxyBuilder<>(LastHttpContent.class, msg).buildProxy();
    }
}
