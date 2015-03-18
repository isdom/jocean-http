package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpRequest;

import java.net.SocketAddress;

public interface ChannelReuser {
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);

    public Channel retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(
            final SocketAddress address, 
            final Channel channel, 
            final ChannelHandler[] removeables);
}
