package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import rx.Observable;
import rx.functions.Action1;

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
        
        public static Action1<ChannelFuture> actionAttachChannelPool(final ChannelPool pool) {
            return new Action1<ChannelFuture>() {
                @Override
                public void call(final ChannelFuture channelFuture) {
                    attachChannelPool(channelFuture.channel(), pool);
                }};
        }
    }
}
