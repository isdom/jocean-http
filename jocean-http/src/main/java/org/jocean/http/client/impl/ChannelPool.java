package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.jocean.http.util.RxNettys.DoOnUnsubscribe;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
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
        
        public static Action1<ChannelFuture> actionEnableRecyclingForNewChannel(
                final ChannelPool pool,
                final DoOnUnsubscribe doOnUnsubscribe) {
            return new Action1<ChannelFuture>() {
                @Override
                public void call(final ChannelFuture channelFuture) {
                    final Channel channel = channelFuture.channel();
                    attachChannelPool(channel, pool);
                    doOnUnsubscribe.call(Subscriptions.create(new Action0() {
                            @Override
                            public void call() {
                                getChannelPool(channel).recycleChannel(channel);
                            }
                        }));
                }};
        }
        
        public static Action1<Channel> actionEnableRecyclingReuseChannel(final Subscriber<?> subscriber) {
            return new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    subscriber.add(Subscriptions.create(new Action0() {
                        @Override
                        public void call() {
                            getChannelPool(channel).recycleChannel(channel);
                        }
                    }));
                }
            };
        }
    }
}
