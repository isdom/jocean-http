package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import rx.Observable;

public interface ChannelPool {
    
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);
    
    public static class Util {
        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
        private static final AttributeKey<Object> READY_ATTR = AttributeKey.valueOf("__READY");
        
        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
            channel.attr(POOL_ATTR).set(pool);
        }
        
        public static ChannelPool getChannelPool(final Channel channel) {
            return  channel.attr(POOL_ATTR).get();
        }
        
        public static void setChannelReady(final Channel channel) {
            channel.attr(READY_ATTR).set(new Object());
        }
        
        public static boolean isChannelReady(final Channel channel) {
            return null != channel.attr(READY_ATTR).get();
        }
    }
}
