package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.io.Closeable;

public interface ChannelCreator extends Closeable {
    public Channel newChannel();
}
