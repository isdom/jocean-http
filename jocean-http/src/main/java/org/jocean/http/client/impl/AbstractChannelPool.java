package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import rx.Observable;
import rx.Subscriber;

public abstract class AbstractChannelPool implements ChannelPool {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    @Override
    public Observable<Channel> retainChannel(final SocketAddress address) {
        return Observable.unsafeCreate(new Observable.OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                findReuseChannel(address, subscriber);
            }});
    }
    
    private void findReuseChannel(final SocketAddress address,
            final Subscriber<? super Channel> subscriber) {
        final Channel channel = reuseChannel(address);
        if (null != channel) {
            final EventLoop eventLoop = channel.eventLoop();
            if (!eventLoop.inEventLoop()) {
                eventLoop.submit(new Runnable() {
                    @Override
                    public void run() {
                        pushActiveChannelOrContinue(address, channel, subscriber);
                    }});
            } else {
                pushActiveChannelOrContinue(address, channel, subscriber);
            }
        } else {
            //  no more channel can be reused
            subscriber.onError(new RuntimeException("Nonreused Channel"));
        }
    }
    
    protected abstract Channel reuseChannel(final SocketAddress address);

    private void pushActiveChannelOrContinue(
            final SocketAddress address,
            final Channel channel, 
            final Subscriber<? super Channel> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            if (channel.isActive()) {
                LOG.info("fetch channel({}) of address ({}) for reuse.", channel, address);
                subscriber.onNext(channel);
                subscriber.onCompleted();
            } else {
                channel.close();
                findReuseChannel(address, subscriber);
            }
        }
    }
}
