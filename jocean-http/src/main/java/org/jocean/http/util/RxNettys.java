package org.jocean.http.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.Observable.OnSubscribe;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static <M> Func1<M, ChannelFuture> sendMessage(
            final Channel channel) {
        return new Func1<M,ChannelFuture>() {
            @Override
            public ChannelFuture call(final M msg) {
                return channel.writeAndFlush(ReferenceCountUtil.retain(msg));
            }};
    }
    
    public static <V, F extends Future<V>,R> Func1<F, Observable<? extends R>> 
        checkFuture(final Class<F> clsF, final Class<R> clsR) {
        return new Func1<F, Observable<? extends R>>() {
            @Override
            public Observable<? extends R> call(final F future) {
                return Observable.create(new OnSubscribe<R> () {
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
}
