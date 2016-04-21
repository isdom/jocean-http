package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

public abstract class AbstractChannelPool implements ChannelPool {
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    @Override
    public Observable<Channel> retainChannel(final SocketAddress address) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                doRetainChannel(address, subscriber);
            }});
    }
    
    private void doRetainChannel(final SocketAddress address,
            final Subscriber<? super Channel> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            try {
                Channel channel;
                do {
                    channel = reuseChannel(address);
                    if (null != channel) {
                        if (channel.isActive()) {
                            subscriber.onNext(channel);
                            subscriber.onCompleted();
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
    }

    protected abstract Channel reuseChannel(final SocketAddress address);
}
