package org.jocean.http.client.impl;

import java.io.Closeable;

import org.jocean.http.util.RxNettys.DoOnUnsubscribe;

import io.netty.channel.ChannelFuture;
import rx.Observable;

public interface ChannelCreator extends Closeable {
    public Observable<? extends ChannelFuture> newChannel(final DoOnUnsubscribe doOnUnsubscribe);
}
