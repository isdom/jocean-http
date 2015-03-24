package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;

import java.net.SocketAddress;

public interface ChannelPool {
    
    public Channel retainChannel(final SocketAddress address);
    
    public boolean recycleChannel(final SocketAddress address, final Channel channel);
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);
}