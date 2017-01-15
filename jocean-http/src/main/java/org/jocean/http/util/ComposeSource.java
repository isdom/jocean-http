package org.jocean.http.util;

import io.netty.channel.Channel;
import rx.Observable;

public interface ComposeSource {
    public <T> Observable.Transformer<T, T> transformer(final Channel channel);
}
