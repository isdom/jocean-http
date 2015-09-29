package org.jocean.http.util;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;

import org.jocean.http.rosa.SignalClient;
import org.jocean.idiom.rx.OneshotSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(RxNettys.class);
    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static final Func1<Object, Object> RETAIN_OBJ = 
            new Func1<Object, Object>() {
        @Override
        public Object call(final Object obj) {
            //    retain obj for blocking
            return ReferenceCountUtil.retain(obj);
        }};
        
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <T> Func1<T, T> retainMap() {
        return (Func1)RETAIN_OBJ;
    }
        
    public static <M> Func1<M, ChannelFuture> sendMessage(
            final Channel channel) {
        return new Func1<M,ChannelFuture>() {
            @Override
            public ChannelFuture call(final M msg) {
                return channel.writeAndFlush(ReferenceCountUtil.retain(msg));
            }};
    }
    
    private final static Func1<Future<Object>, Observable<Object>> EMITERROR_ONFAILURE = 
    new Func1<Future<Object>, Observable<Object>>() {
        @Override
        public Observable<Object> call(final Future<Object> future) {
            return Observable.create(new OnSubscribe<Object> () {
                @Override
                public void call(final Subscriber<Object> subscriber) {
                    subscriber.add(Subscriptions.from(
                        future.addListener(new GenericFutureListener<Future<Object>>() {
                            @Override
                            public void operationComplete(final Future<Object> f)
                                    throws Exception {
                                if (!f.isSuccess()) {
                                    subscriber.onError(f.cause());
                                }
                            }
                        })));
                }});
        }};
        
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <F extends Future<?>,R> Func1<F, Observable<? extends R>> 
        emitErrorOnFailure() {
        return (Func1)EMITERROR_ONFAILURE;
        /* replace by global one instance
        return new Func1<F, Observable<? extends R>>() {
            @Override
            public Observable<? extends R> call(final F future) {
                return Observable.create(new OnSubscribe<R> () {
                    @SuppressWarnings("unchecked")
                    @Override
                    public void call(final Subscriber<? super R> subscriber) {
                        subscriber.add(Subscriptions.from(
                            future.addListener(new GenericFutureListener<F>() {
                                @Override
                                public void operationComplete(final F future)
                                        throws Exception {
                                    if (!future.isSuccess()) {
                                        subscriber.onError(future.cause());
                                    }
                                }
                            })));
                    }});
            }};
            */
    }
    
    private final static Func1<ChannelFuture, Observable<? extends Channel>> EMITNEXTANDCOMPLETED_ONSUCCESS = 
    new Func1<ChannelFuture, Observable<? extends Channel>>() {
        @Override
        public Observable<? extends Channel> call(final ChannelFuture future) {
            return Observable.create(new OnSubscribe<Channel>() {
                @Override
                public void call(final Subscriber<? super Channel> subscriber) {
                    subscriber.add(Subscriptions.from(
                        future.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture f)
                                    throws Exception {
                                if (f.isSuccess()) {
                                    subscriber.onNext(f.channel());
                                    subscriber.onCompleted();
                                }
                            }
                        })));
                }});
        }};
        
    public static Func1<ChannelFuture, Observable<? extends Channel>> 
        emitNextAndCompletedOnSuccess() {
        return  EMITNEXTANDCOMPLETED_ONSUCCESS;
    }
    
    public static Subscription subscriptionFrom(final Channel channel) {
        return new OneshotSubscription() {
            @Override
            protected void doUnsubscribe() {
                channel.close();
            }};
    }

    public static Subscription removeHandlersSubscription(final Channel channel, final String[] names) {
        return new OneshotSubscription() {
            @Override
            protected void doUnsubscribe() {
                if (channel.eventLoop().inEventLoop()) {
                    doRemove();
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            doRemove();
                        }});
                }
            }

            private void doRemove() {
                final ChannelPipeline pipeline = channel.pipeline();
                for (String name : names) {
                    if (pipeline.context(name) != null) {
                        final ChannelHandler handler = pipeline.remove(name);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("channel({}): remove oneoff Handler({}/{}) success.", channel, name, handler);
                        }
                    }
                }
            }};
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
    
    private static final Func1<Object,Boolean> _ISHTTPOBJ = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return obj instanceof HttpObject;
        }};
    private static final Func1<Object,HttpObject> _OBJ2HTTPOBJ = new Func1<Object, HttpObject> (){
        @Override
        public HttpObject call(final Object obj) {
            return (HttpObject)obj;
        }};
        
    private static final Transformer<Object, HttpObject> _OBJS2HTTPOBJS = 
        new Transformer<Object, HttpObject>() {
        @Override
        public Observable<HttpObject> call(final Observable<Object> objs) {
            return objs.filter(_ISHTTPOBJ).map(_OBJ2HTTPOBJ);
        }};
        
    public static Transformer<Object, HttpObject> objects2httpobjs() {
        return _OBJS2HTTPOBJS;
    }
    
    private static final Func1<Object,Boolean> _PROGRESS = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return !(obj instanceof SignalClient.Progressable);
        }};
            
    public static <T> Transformer<Object, T> filterProgress() {
        return new Transformer<Object, T>() {
            @Override
            public Observable<T> call(final Observable<Object> objs) {
                return objs.filter(_PROGRESS).map(new Func1<Object, T> (){
                    @SuppressWarnings("unchecked")
                    @Override
                    public T call(final Object obj) {
                        return (T)obj;
                    }});
            }};
    }
    
    public static <T> void releaseObjects(final Collection<T> objs) {
        synchronized (objs) {
            for ( T obj : objs ) {
                if (ReferenceCountUtil.release(obj)) {
                    LOG.debug("({}) is release and deallocated success.", obj); 
                } else {
//                    if ( obj instanceof ReferenceCounted) {
//                        LOG.warn("HttpObject({}) is !NOT! released.", obj); 
//                    }
                }
            }
            objs.clear();
        }
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
    
}
