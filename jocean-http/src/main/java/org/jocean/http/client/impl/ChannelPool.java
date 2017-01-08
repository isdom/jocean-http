package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.jocean.http.util.Nettys;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Single;
import rx.functions.Action0;
import rx.functions.Action1;

public interface ChannelPool {
    
    public Single<Channel> retainChannelAsSingle(final SocketAddress address);
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
    
    public void preSendRequest(final Channel channel, final HttpRequest request);
    
    public void postReceiveLastContent(final Channel channel);
    
    public static class Util {
        private static final Action1<Channel> RECYCLE_TO_POOL = new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                getChannelPool(channel).recycleChannel(channel);
            }
        };
        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
        
        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
            channel.attr(POOL_ATTR).set(pool);
        }
        
        public static ChannelPool getChannelPool(final Channel channel) {
            return  channel.attr(POOL_ATTR).get();
        }
        
        public static Transformer<Object,Object> hookPreSendHttpRequest(final Channel channel) {
            return new Transformer<Object,Object>() {
                @Override
                public Observable<Object> call(final Observable<Object> source) {
                    return source.doOnNext(new Action1<Object> () {
                        @Override
                        public void call(final Object msg) {
                            if (msg instanceof HttpRequest) {
                                final ChannelPool pool = getChannelPool(channel);
                                if (null!=pool) {
                                    pool.preSendRequest(channel, (HttpRequest)msg);
                                }
                            }
                        }});
                }};
        }
        
        public static <T> Transformer<T,T> hookPostReceiveLastContent(final Channel channel) {
            return new Transformer<T,T>() {

                @Override
                public Observable<T> call(final Observable<T> source) {
                    return source.doOnCompleted(new Action0 () {
                        @Override
                        public void call() {
                            final ChannelPool pool = getChannelPool(channel);
                            if (null!=pool) {
                                pool.postReceiveLastContent(channel);
                            }
                        }});
                }};
        }
        
        public static Action1<Channel> attachToChannelPoolAndEnableRecycle(
                final ChannelPool pool) {
            return new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    attachChannelPool(channel, pool);
                    Nettys.setReleaseAction(channel, RECYCLE_TO_POOL);
                }};
        }
    }
}
