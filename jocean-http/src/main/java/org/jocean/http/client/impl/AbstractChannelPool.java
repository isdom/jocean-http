package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.functions.Action1;
import rx.Subscriber;
import rx.Subscription;
import rx.subscriptions.Subscriptions;

public abstract class AbstractChannelPool implements ChannelPool {
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    @Override
    public Observable<Channel> retainChannel(final SocketAddress address,
            final Action1<Subscription> add4release) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                doRetainChannel(address, subscriber, add4release);
            }});
    }
    
    private void doRetainChannel(final SocketAddress address,
            final Subscriber<? super Channel> subscriber, 
            final Action1<Subscription> add4release) {
        if (!subscriber.isUnsubscribed()) {
            try {
                final Channel channel = reuseChannel(address);
                if (null!=channel) {
                    final Runnable runnable = buildOnNextRunnable(address, subscriber, channel, add4release);
                    if (channel.eventLoop().inEventLoop()) {
                        runnable.run();
                    } else {
                        @SuppressWarnings("unchecked")
                        final Future<Object> future = (Future<Object>) channel.eventLoop().submit(runnable);
                        add4release.call(Subscriptions.from(future));
                        future.addListener(RxNettys.makeFailure2ErrorListener(subscriber));
                    }
                } else {
                    subscriber.onError(new RuntimeException("Nonreused Channel"));
                }
            } catch (Throwable e) {
                subscriber.onError(e);
            }
        }
    }

    private Runnable buildOnNextRunnable(
            final SocketAddress address, 
            final Subscriber<? super Channel> subscriber,
            final Channel channel, 
            final Action1<Subscription> add4release) {
        return new Runnable() {
            @Override
            public void run() {
                if (channel.isActive()) {
                    subscriber.onNext(channel);
                    subscriber.onCompleted();
                } else {
                    channel.close();
                    doRetainChannel(address, subscriber, add4release);
                }
            }};
    }

    protected abstract Channel reuseChannel(final SocketAddress address);
}
