package org.jocean.http.client.impl;

import java.io.Closeable;

import org.jocean.http.util.RxNettys.DoOnUnsubscribe;

import io.netty.channel.ChannelFuture;
import rx.Single;

public interface ChannelCreator extends Closeable {
    public Single<? extends ChannelFuture> newChannel(final DoOnUnsubscribe doOnUnsubscribe);
}
