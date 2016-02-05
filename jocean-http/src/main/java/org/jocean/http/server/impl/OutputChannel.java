package org.jocean.http.server.impl;

import io.netty.channel.Channel;

public interface OutputChannel {
    public void output(final Object msg);
    public void onResponseCompleted(final boolean isKeepAlive);
    public Channel channel();
}
