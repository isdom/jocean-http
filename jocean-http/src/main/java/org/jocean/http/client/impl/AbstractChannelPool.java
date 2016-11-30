package org.jocean.http.client.impl;

import java.net.SocketAddress;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.Subscriber;

public abstract class AbstractChannelPool implements ChannelPool {
    
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    @Override
    public Single<Channel> retainChannelAsSingle(final SocketAddress address) {
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
                                    LOG.info("fetch channel({}) of address ({}) for reuse.", channel, address);
                                    subscriber.onSuccess(channel);
                                    return;
                                } else {
                                    LOG.info("fetch inactive channel({}) of address ({}) for reuse, drop it.", channel, address);
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
    
    @Override
    public Observable<Channel> retainChannel(final SocketAddress address) {
        return Observable.create(new Observable.OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        Channel channel;
                        do {
                            channel = reuseChannel(address);
                            if (null != channel) {
                                if (channel.isActive()) {
                                    LOG.info("fetch channel({}) of address ({}) for reuse.", channel, address);
                                    RxNettys.installDoOnUnsubscribe(channel, DoOnUnsubscribe.Util.from(subscriber));
                                    subscriber.add(RxNettys.subscriptionForReleaseChannel(channel));
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
            }});
    }
    
    protected abstract Channel reuseChannel(final SocketAddress address);
}
