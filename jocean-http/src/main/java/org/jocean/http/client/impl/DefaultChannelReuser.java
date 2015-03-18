package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public class DefaultChannelReuser extends AbstractChannelReuser {

    private static final AttributeKey<Object> REUSE = AttributeKey.valueOf("REUSE");
    private static final Object OK = new Object();
    private static final AttributeKey<Boolean> KEEPALIVE = AttributeKey.valueOf("KEEPALIVE");
    
    @Override
    public void setChannelKeepAlive(final Channel channel, final boolean isKeepAlive) {
        channel.attr(KEEPALIVE).set(isKeepAlive);
    }

    @Override
    public boolean isChannelKeepAlive(final Channel channel) {
        return channel.attr(KEEPALIVE).get();
    }

    @Override
    public void markChannelReused(final Channel channel) {
        channel.attr(REUSE).set(OK);
    }

    @Override
    public boolean isChannelReused(final Channel channel) {
        return null != channel.attr(REUSE).get();
    }

    @Override
    public void resetChannelReused(final Channel channel) {
        channel.attr(REUSE).remove();
    }

}
