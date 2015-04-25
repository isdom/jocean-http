package org.jocean.http.client.impl;

import io.netty.channel.ChannelFuture;

import java.io.Closeable;

public interface ChannelCreator extends Closeable {
    public ChannelFuture newChannel();
}
