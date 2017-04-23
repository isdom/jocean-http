package org.jocean.http.client.impl;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import rx.Observable;

public interface ChannelPool {
    
    public Observable<Channel> retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final Channel channel);
}
