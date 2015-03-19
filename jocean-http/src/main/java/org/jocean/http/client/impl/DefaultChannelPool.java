package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final AttributeKey<Object> REUSE = AttributeKey.valueOf("REUSE");
    private static final Object OK = new Object();
    private static final AttributeKey<Boolean> KEEPALIVE = AttributeKey.valueOf("KEEPALIVE");
    
    @Override
    public void beforeSendRequest(final Channel channel, final HttpRequest request) {
        channel.attr(KEEPALIVE).set(HttpHeaders.isKeepAlive(request));
    }

    @Override
    public void afterReceiveLastContent(final Channel channel) {
        if (channel.attr(KEEPALIVE).get()) {
            channel.attr(REUSE).set(OK);
        }
    }

    @Override
    public boolean recycleChannel(final SocketAddress address, final Channel channel) {
        if (null != channel.attr(REUSE).get()) {
            channel.attr(REUSE).remove();
            getOrCreateChannels(address).add(channel);
            return true;
        }
        else {
            return false;
        }
    }

}
