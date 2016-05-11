package org.jocean.http.client.impl;

import java.io.Closeable;

import org.jocean.http.util.RxNettys.DoOnUnsubscribe;

import io.netty.channel.Channel;
import rx.Single;

public interface ChannelCreator extends Closeable {
    public Single<? extends Channel> newChannel(final DoOnUnsubscribe doOnUnsubscribe);
}
