package org.jocean.http.util;

import io.netty.channel.Channel;

public interface ChannelAware {
    public void setChannel(final Channel channel);
    public Channel getChannel();
}
