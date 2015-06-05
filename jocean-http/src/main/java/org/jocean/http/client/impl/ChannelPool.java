package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;

import org.jocean.http.client.Outbound.Feature;

import rx.Observable;

public interface ChannelPool {
    
    public Observable<Channel> retainChannel(
            final SocketAddress address, 
            final Feature[] features);
    
    public boolean recycleChannel(final Channel channel);
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);
    
    public static class Util {
        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
        
        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
            channel.attr(POOL_ATTR).set(pool);
        }
        
        public static ChannelPool getChannelPool(final Channel channel) {
            return  channel.attr(POOL_ATTR).get();
        }
    }
}
