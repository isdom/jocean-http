package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;

import rx.Observable;
import rx.functions.Func1;

public interface ChannelPool {
    
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);
    
    public static class Util {
        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
        private static final AttributeKey<Func1<Channel, Boolean>> ISREADY_ATTR = AttributeKey.valueOf("__ISREADY");
        
        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
            channel.attr(POOL_ATTR).set(pool);
        }
        
        public static ChannelPool getChannelPool(final Channel channel) {
            return  channel.attr(POOL_ATTR).get();
        }
        
        public static void attachIsReady(final Channel channel, final Func1<Channel, Boolean> isReady) {
            channel.attr(ISREADY_ATTR).set(isReady);
        }
        
        public static boolean isChannelReady(final Channel channel) {
            final Func1<Channel, Boolean> isReady = channel.attr(ISREADY_ATTR).get();
            return  null != isReady ? isReady.call(channel) : false;
        }
    }
}
