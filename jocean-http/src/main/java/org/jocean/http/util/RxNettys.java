package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.jocean.http.Feature;
import org.jocean.http.Feature.FeatureOverChannelHandler;
import org.jocean.http.Feature.HandlerBuilder;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ProxyBuilder;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.idiom.store.BlobRepo.Blob;
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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
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
        
    public static <T, V> Observable<T> observableFromFuture(final Future<V> future) {
        return Observable.create(new Observable.OnSubscribe<T>() {
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
        
    public static Observable.Transformer<? super HttpObject, ? extends Blob> postRequest2Blob() {
        return postRequest2Blob(null);
    }
    
    public static Observable.Transformer<? super HttpObject, ? extends Blob> postRequest2Blob(
            final String contentTypePrefix) {
        return new Observable.Transformer<HttpObject, Blob>() {
            @Override
            public Observable<Blob> call(final Observable<HttpObject> source) {
                return source.flatMap(new AsBlob(contentTypePrefix))
                        .compose(RxObservables.<Blob>ensureSubscribeAtmostOnce())
                        ;
            }};
    }
    
    static class AsBlob implements Func1<HttpObject, Observable<? extends Blob>> {

        private final String _contentTypePrefix;
        
        private boolean _isMultipart = false;
        
        //  TODO , at least call _postDecoder.destroy();
        private HttpPostRequestDecoder _postDecoder = null;
        
        private static final HttpDataFactory HTTP_DATA_FACTORY =
                new DefaultHttpDataFactory(false);  // DO NOT use Disk
        
        public AsBlob(final String contentTypePrefix) {
            this._contentTypePrefix = contentTypePrefix;
        }

        @Override
        public Observable<? extends Blob> call(final HttpObject msg) {
            if (msg instanceof HttpRequest) {
                final HttpRequest request = (HttpRequest)msg;
                if ( request.method().equals(HttpMethod.POST)
                        && HttpPostRequestDecoder.isMultipart(request)) {
                    _isMultipart = true;
                    _postDecoder = new HttpPostRequestDecoder(
                            HTTP_DATA_FACTORY, request);
                    
                    LOG.info("{} isMultipart", msg);
                } else {
                    _isMultipart = false;
                    LOG.info("{} is !NOT! Multipart", msg);
                }
            }
            if (msg instanceof HttpContent && _isMultipart && (null != _postDecoder)) {
                return onNext4Multipart((HttpContent)msg);
            } else {
                return Observable.empty();
            }
        }

        private Observable<? extends Blob> onNext4Multipart(
                final HttpContent content) {
            try {
                _postDecoder.offer(content);
            } catch (ErrorDataDecoderException e) {
                LOG.warn("exception when postDecoder.offer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
            final List<Blob> blobs = new ArrayList<>();
            try {
                while (_postDecoder.hasNext()) {
                    final InterfaceHttpData data = _postDecoder.next();
                    if (data != null) {
                        try {
                            final Blob blob = processHttpData(data);
                            if (null != blob) {
                                blobs.add(blob);
                                LOG.info("onNext4Multipart: add Blob {}", blob);
                            }
                        } finally {
                            data.release();
                        }
                    }
                }
            } catch (EndOfDataDecoderException e) {
                LOG.warn("exception when postDecoder.hasNext, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
            return blobs.isEmpty() ? Observable.<Blob>empty() : Observable.from(blobs);
        }

        private Blob processHttpData(final InterfaceHttpData data) {
            if (data.getHttpDataType().equals(
                InterfaceHttpData.HttpDataType.FileUpload)) {
                final FileUpload fileUpload = (FileUpload)data;
                
                //  if _contentTypePrefix is not null, try to match
                if (null != _contentTypePrefix 
                    && !fileUpload.getContentType().startsWith(_contentTypePrefix)) {
                    LOG.info("fileUpload's contentType is {}, NOT match prefix {}, so ignore",
                            fileUpload.getContentType(), _contentTypePrefix);
                    return null;
                }
                    
                try {
                    final byte[] bytes = Nettys.dumpByteBufAsBytes(fileUpload.content());
                    final String contentType = fileUpload.getContentType();
                    final String filename = fileUpload.getFilename();
                    final String name = fileUpload.getName();
                    return new Blob() {
                        @Override
                        public String toString() {
                            final StringBuilder builder = new StringBuilder();
                            builder.append("Blob [name=").append(name())
                                    .append(", filename=").append(filename())
                                    .append(", contentType=").append(contentType())
                                    .append(", content.size=").append(content().length)
                                    .append("]");
                            return builder.toString();
                        }
                        
                        @Override
                        public byte[] content() {
                            return bytes;
                        }
                        @Override
                        public String contentType() {
                            return contentType;
                        }
                        @Override
                        public String name() {
                            return name;
                        }
                        @Override
                        public String filename() {
                            return filename;
                        }};
                } catch (IOException e) {
                    LOG.warn("exception when Nettys.dumpByteBufAsBytes, detail: {}",
                            ExceptionUtils.exception2detail(e));
                }
                
            } else {
                LOG.info("InterfaceHttpData ({}) is NOT fileUpload, so ignore", data);
            }
            return null;
        };
    }

    private static final AttributeKey<Object> __PROCESSOR = AttributeKey.valueOf("__TRADE_PROCESSOR");
    
    public static <T> T attachProcessorToChannel(final Channel channel, final T processor) {
        channel.attr(__PROCESSOR).setIfAbsent(processor);
        return processor;
    }
    
    public static <T> void detachProcessorFromChannel(final Channel channel, final T processor) {
        channel.attr(__PROCESSOR).compareAndSet(processor, null);
    }
    
    public static boolean hasProcessorForChannel(final Channel channel) {
        return channel.attr(__PROCESSOR).get() != null;
    }
    
    public static Object getProcessorForChannel(final Channel channel) {
        return channel.attr(__PROCESSOR).get();
    }
}
