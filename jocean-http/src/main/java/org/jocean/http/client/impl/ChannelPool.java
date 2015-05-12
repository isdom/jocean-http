package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;

import java.net.SocketAddress;

import org.jocean.http.client.OutboundFeature;

import rx.Observable;

public interface ChannelPool {
    
    public Observable<? extends Channel> retainChannel(final SocketAddress address, 
            final OutboundFeature.Applicable[] features);
    
    public boolean recycleChannel(final SocketAddress address, final Channel channel);
    
    public void beforeSendRequest(final Channel channel, final HttpRequest request);
    
    public void afterReceiveLastContent(final Channel channel);
}
