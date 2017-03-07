package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import rx.Observable;

public interface ChannelCreator extends AutoCloseable {
    public void close();
    public Observable<? extends Channel> newChannel();
}
