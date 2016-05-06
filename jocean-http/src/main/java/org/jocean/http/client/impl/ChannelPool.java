package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public interface ChannelPool {
    
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
    
    public void preSendRequest(final Channel channel, final HttpRequest request);
    
    public void postReceiveLastContent(final Channel channel);
    
    public static class Util {
        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
        
        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
            channel.attr(POOL_ATTR).set(pool);
        }
        
        public static ChannelPool getChannelPool(final Channel channel) {
            return  channel.attr(POOL_ATTR).get();
        }
        
        public static Action1<ChannelFuture> actionEnableRecyclingForChannelFuture(
                final ChannelPool pool,
                final Subscriber<?> subscriber) {
            return new Action1<ChannelFuture>() {
                @Override
                public void call(final ChannelFuture channelFuture) {
                    final Channel channel = channelFuture.channel();
                    attachChannelPool(channel, pool);
                    subscriber.add(Subscriptions.create(new Action0() {
                            @Override
                            public void call() {
                                getChannelPool(channel).recycleChannel(channel);
                            }
                        }));
                }};
        }
        
        public static Func1<Channel, Observable<? extends Channel>> funcEnableRecycling() {
            return new Func1<Channel, Observable<? extends Channel>>() {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    return Observable.create(new OnSubscribe<Channel>() {
                        @Override
                        public void call(final Subscriber<? super Channel> subscriber) {
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.add(Subscriptions.create(new Action0() {
                                        @Override
                                        public void call() {
                                            getChannelPool(channel).recycleChannel(channel);
                                        }
                                    }));
                                subscriber.onNext(channel);
//                                subscriber.onCompleted();
                            }
                        }
                    });
                }};
        }
    }
}
