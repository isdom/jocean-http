package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import rx.Observable;
import rx.Single;

public interface ChannelPool {
    
    public Single<Channel> retainChannelAsSingle(final SocketAddress address);
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
}
