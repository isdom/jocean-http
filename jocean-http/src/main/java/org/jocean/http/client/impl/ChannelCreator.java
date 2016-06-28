package org.jocean.http.client.impl;

import java.io.Closeable;

import org.jocean.http.DoOnUnsubscribe;

import io.netty.channel.Channel;
import rx.Observable;
import rx.Single;

public interface ChannelCreator extends Closeable {
    public Single<? extends Channel> newChannelAsSingle(final DoOnUnsubscribe doOnUnsubscribe);
    public Observable<? extends Channel> newChannel();
}
