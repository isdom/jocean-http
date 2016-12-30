package org.jocean.http.client.impl;

import java.io.Closeable;

import io.netty.channel.Channel;
import rx.Observable;

public interface ChannelCreator extends Closeable {
    public Observable<? extends Channel> newChannel();
}
