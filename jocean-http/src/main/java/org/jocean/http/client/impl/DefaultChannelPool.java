package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final AttributeKey<Object> TRANSACTIONING = AttributeKey.valueOf("__TRANSACTIONING");
    private static final Object OK = new Object();
    private static final AttributeKey<Boolean> KEEPALIVE = AttributeKey.valueOf("__KEEPALIVE");
    
    @Override
    public void beforeSendRequest(final Channel channel, final HttpRequest request) {
        //  当Channel被重用，但由于source cancel等情况，没有发送过request
        //  则此时仍然可以被再次回收
        channel.attr(TRANSACTIONING).set(OK);
        channel.attr(KEEPALIVE).set(HttpHeaders.isKeepAlive(request));
    }

    @Override
    public void afterReceiveLastContent(final Channel channel) {
        if (channel.attr(KEEPALIVE).get()) {
            channel.attr(TRANSACTIONING).remove();
        }
    }

    @Override
    public boolean recycleChannel(final SocketAddress address, final Channel channel) {
        if (null == channel.attr(TRANSACTIONING).get()) {
            getOrCreateChannels(address).add(channel);
            return true;
        }
        else {
            return false;
        }
    }

}
