package org.jocean.http.client.internal;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import rx.Observable;
import rx.functions.Func0;

public interface ChannelPool {

    public Observable<Channel> retainChannel(final SocketAddress address);
    public Observable<Channel> retainChannel(final Func0<SocketAddress> addressProvider);

    public boolean recycleChannel(final Channel channel);
}
