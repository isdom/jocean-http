package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import rx.Observable;
import rx.Single;

public interface ChannelPool {
    
    public Single<Channel> retainChannelAsSingle(final SocketAddress address);
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
    
    public static class Util {
//        private static final Action1<Channel> RECYCLE_TO_POOL = new Action1<Channel>() {
//            @Override
//            public void call(final Channel channel) {
//                getChannelPool(channel).recycleChannel(channel);
//            }
//        };
//        private static final AttributeKey<ChannelPool> POOL_ATTR = AttributeKey.valueOf("__POOL");
//        
//        public static void attachChannelPool(final Channel channel, final ChannelPool pool) {
//            channel.attr(POOL_ATTR).set(pool);
//        }
//        
//        public static ChannelPool getChannelPool(final Channel channel) {
//            return  channel.attr(POOL_ATTR).get();
//        }
        
//        public static Action1<Channel> attachToChannelPoolAndEnableRecycle(
//                final ChannelPool pool) {
//            return new Action1<Channel>() {
//                @Override
//                public void call(final Channel channel) {
//                    attachChannelPool(channel, pool);
//                    Nettys.setReleaseAction(channel, RECYCLE_TO_POOL);
//                }};
//        }
    }
}
