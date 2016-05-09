package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.jocean.http.Feature;
import org.jocean.http.Feature.HandlerBuilder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
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

    public interface DoOnUnsubscribe extends Action1<Subscription> {
    }
    
    static public class DefaultDoOnUnsubscribe implements DoOnUnsubscribe {

        @Override
        public void call(Subscription s) {
            this._subscriptionList.add(s);
        }
        
        public void unsubscribe() {
            this._subscriptionList.unsubscribe();
        }
        
        final private SubscriptionList _subscriptionList = new SubscriptionList();
    }
    
    public static void applyFeaturesToChannel(
            final Channel channel,
            final HandlerBuilder builder,
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        for (Feature feature : features) {
            final ChannelHandler handler = feature.call(builder, channel.pipeline());
            if (null != handler && null!=doOnUnsubscribe) {
                doOnUnsubscribe.call(
                    Subscriptions.create(
                        RxNettys.actionToRemoveHandler(channel, handler)));
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
    
    public static <V> void attachFutureToSubscriber(final Future<V> future, final Subscriber<?> subscriber) {
        subscriber.add(Subscriptions.from(future));
        future.addListener(RxNettys.listenerOfOnError(subscriber));
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
    
    public static Func1<ChannelFuture, Observable<? extends Channel>> funcFutureToChannel() {
        return new Func1<ChannelFuture, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final ChannelFuture future) {
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            future.addListener(listenerOfOnError(subscriber));
                            future.addListener(listenerOfOnNextAndCompleted(subscriber));
                        }
                    }});
            }};
    }
    
    public static Func1<Channel, Observable<? extends Channel>> funcAsyncConnectTo(
            final SocketAddress remoteAddress,
            final DoOnUnsubscribe doOnUnsubscribe) {
        return new Func1<Channel, Observable<? extends Channel>>() {
            @Override
            public Observable<? extends Channel> call(final Channel channel) {
                return Observable.create(new OnSubscribe<Channel>() {
                    @Override
                    public void call(final Subscriber<? super Channel> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            final ChannelFuture future = channel.connect(remoteAddress);
                            doOnUnsubscribe.call(Subscriptions.from(future));
                            future.addListener(listenerOfOnError(subscriber))
                                .addListener(listenerOfOnNextAndCompleted(subscriber));
                        }
                    }});
            }};
    }
    
    public static <M> Transformer<M, Channel> sendRequestAndReturnChannel(
            final Channel channel, 
            final DoOnUnsubscribe doOnUnsubscribe) {
        return new Transformer<M, Channel>() {
            @Override
            public Observable<Channel> call(final Observable<M> request) {
                return request.doOnCompleted(new Action0() {
                          @Override
                          public void call() {
                              channel.flush();
                          }})
                      .map(new Func1<M, ChannelFuture>() {
                          @Override
                          public ChannelFuture call(final M msg) {
                              final ChannelFuture future = channel.write(ReferenceCountUtil.retain(msg));
                              doOnUnsubscribe.call(Subscriptions.from(future));
                              return future;
                          }
                      })
                      .flatMap(funcFutureToChannel())
                      .last();
            };
        };
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
            final Feature[] features,
            final DoOnUnsubscribe doOnUnsubscribe) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                applyFeaturesToChannel(
                        channel, 
                        builder, 
                        features, 
                        doOnUnsubscribe);
            }};
    }
    
    public static Transformer<? super Channel, ? extends Channel> markChannelWhenReady(final boolean isSSLEnabled) {
        return new Transformer<Channel, Channel>() {
            @Override
            public Observable<Channel> call(final Observable<Channel> source) {
                if (isSSLEnabled) {
                    return source.flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                        @Override
                        public Observable<? extends Channel> call(final Channel channel) {
                            return Observable.create(new OnSubscribe<Channel>() {
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
    
    private final static Func1<Object, Observable<Object>> TONEVER_FLATMAP = 
        new Func1<Object, Observable<Object>>() {
            @Override
            public Observable<Object> call(final Object source) {
                return Observable.never();
            }};
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <SRC, DST> Func1<SRC, Observable<? extends DST>>  flatMapByNever() {
        return (Func1)TONEVER_FLATMAP;
    }
    
    public static Subscription subscriptionFrom(final Channel channel) {
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
    
    public static final Func1<Object, Boolean> NOT_HTTPOBJECT = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return !(obj instanceof HttpObject);
        }};
        
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
    
    public static <T, E extends T> Transformer<? super T, ? extends T> retainAtFirst(
            final Collection<E> objs, 
            final Class<E> elementCls) {
        return new Transformer<T, T>() {
            @Override
            public Observable<T> call(final Observable<T> source) {
                return source.doOnNext(new Action1<T>() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public void call(final T input) {
                        if (input != null && elementCls.isAssignableFrom(input.getClass())) {
                            objs.add(ReferenceCountUtil.retain((E)input));
                            if ( LOG.isDebugEnabled()) {
                                if ( input instanceof ReferenceCounted) {
                                    LOG.debug("({}) retaind,so refcnt is {}.", input, ((ReferenceCounted)input).refCnt()); 
                                }
                            }
                        }
                    }});
            }};
    }
    
    public static <E, T> Transformer<? super T, ? extends T> releaseAtLast(final Collection<E> objs) {
        return new Transformer<T, T>() {
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
    
    public static FullHttpResponse retainAsFullHttpResponse(final List<HttpObject> httpObjs) {
        if (httpObjs.size()>0) {
            if (httpObjs.get(0) instanceof FullHttpResponse) {
                return ((FullHttpResponse)httpObjs.get(0)).retain();
            }
            
            final HttpResponse resp = (HttpResponse)httpObjs.get(0);
            final ByteBuf[] bufs = new ByteBuf[httpObjs.size()-1];
            for (int idx = 1; idx<httpObjs.size(); idx++) {
                bufs[idx-1] = ((HttpContent)httpObjs.get(idx)).content().retain();
            }
            return new DefaultFullHttpResponse(
                    resp.getProtocolVersion(), 
                    resp.getStatus(),
                    Unpooled.wrappedBuffer(bufs));
        } else {
            return null;
        }
    }
    
    public static Observable<HttpObject> httpobjObservable(final Channel channel) {
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    APPLY.HTTPOBJ_SUBSCRIBER.applyTo(channel.pipeline(), subscriber);
                }
            }} );
    }
}
