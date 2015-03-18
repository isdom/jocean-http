package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.net.SocketAddress;

public interface ChannelReuser {
    
    public void setChannelKeepAlive(final Channel channel, final boolean isKeepAlive);
    
    public boolean isChannelKeepAlive(final Channel channel);

    public void markChannelReused(final Channel channel);
        
    public boolean isChannelReused(final Channel channel);
    
    public void resetChannelReused(final Channel channel);

    public Channel retainChannelFromPool(final SocketAddress address);
    
    public void releaseChannelToPool(final SocketAddress address, final Channel channel);
}
