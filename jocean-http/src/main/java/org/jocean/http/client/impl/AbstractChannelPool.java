package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import rx.Single;
import rx.SingleSubscriber;

public abstract class AbstractChannelPool implements ChannelPool {
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    @Override
    public Single<Channel> retainChannel(final SocketAddress address) {
        return Single.create(new Single.OnSubscribe<Channel>() {
            @Override
            public void call(final SingleSubscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        Channel channel;
                        do {
                            channel = reuseChannel(address);
                            if (null != channel) {
                                if (channel.isActive()) {
                                    subscriber.onSuccess(channel);
                                    return;
                                } else {
                                    channel.close();
                                }
                            }
                        } while (null!=channel);
                        //  no more channel can be reused
                        subscriber.onError(new RuntimeException("Nonreused Channel"));
                    } catch (Throwable e) {
                        subscriber.onError(e);
                    }
                }
            }});
    }
    
    protected abstract Channel reuseChannel(final SocketAddress address);
}
