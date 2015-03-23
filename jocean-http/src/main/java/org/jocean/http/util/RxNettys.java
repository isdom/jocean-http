package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class RxNettys {
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
    
    private final static Func1<Future<Object>, Observable<Object>> CHECK_FUTURE = 
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
        checkFuture() {
        return (Func1)CHECK_FUTURE;
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
    
    public static Subscription channelSubscription(final Channel channel) {
        return new Subscription() {
            @Override
            public void unsubscribe() {
                channel.close();
            }
            @Override
            public boolean isUnsubscribed() {
                return !channel.isActive();
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
}
