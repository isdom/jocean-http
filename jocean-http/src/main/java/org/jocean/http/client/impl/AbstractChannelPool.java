package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jocean.http.client.OutboundFeature;
import org.jocean.http.util.RxNettys;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func1;

public abstract class AbstractChannelPool implements ChannelPool {

    protected AbstractChannelPool(final ChannelCreator channelCreator) {
        this._channelCreator = channelCreator;
    }
    
    @Override
    public Observable<? extends Channel> retainChannel(final SocketAddress address,
            final OutboundFeature.Applicable[] features) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        final Channel channel = reuseChannel(address);
                        if (null!=channel) {
                            if (channel.eventLoop().inEventLoop()) {
                                subscriber.onNext(channel);
                                subscriber.onCompleted();
                            } else {
                                onNextReuseChannelInEventloop(subscriber, channel);
                            }
                        } else {
                            onNextNewChannel(subscriber, address, features);
                        }
                    } catch (Throwable e) {
                        subscriber.onError(e);
                    }
                }
            }});
    }
    
    private Channel reuseChannel(final SocketAddress address) {
        final Queue<Channel> channels = getChannels(address);
        if (null == channels) {
            return null;
        }
        Channel channel = null;
        do {
            channel = channels.poll();
        } while (null != channel && !channel.isActive());
        return channel;
    }

    protected Queue<Channel> getOrCreateChannels(final SocketAddress address) {
        final Queue<Channel> channels = this._channels.get(address);
        if (null == channels) {
            final Queue<Channel> newChannels = new ConcurrentLinkedQueue<Channel>();
            final Queue<Channel> previous = this._channels.putIfAbsent(address, newChannels);
            return  null!=previous ? previous : newChannels;
        }
        else {
            return channels;
        }
    }
    
    protected Queue<Channel> getChannels(final SocketAddress address) {
        return this._channels.get(address);
    }
    
    private void onNextReuseChannelInEventloop(
            final Subscriber<? super Channel> subscriber, final Channel channel) {
        final Future<?> future = 
            channel.eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    subscriber.onNext(channel);
                    subscriber.onCompleted();
                }});
        RxNettys.<Future<?>,Channel>emitErrorOnFailure()
            .call(future)
            .subscribe(subscriber);
    }

    private void onNextNewChannel(
            final Subscriber<? super Channel> subscriber,
            final SocketAddress address,
            final OutboundFeature.Applicable[] features) {
        final ChannelFuture future = _channelCreator.newChannel();
        RxNettys.<ChannelFuture,Channel>emitErrorOnFailure()
            .call(future)
            .subscribe(subscriber);
        RxNettys.emitNextAndCompletedOnSuccess()
            .call(future)
            .flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    for ( OutboundFeature.Applicable applicable : features) {
                        if (!applicable.isRemovable()) {
                            applicable.call(channel);
                        }
                    }
                    OutboundFeature.HTTPCLIENT_CODEC.applyTo(channel);
                    OutboundFeature.CHUNKED_WRITER.applyTo(channel);
                    OutboundFeature.CONNECTING_NOTIFIER.applyTo(
                        channel, 
                        OutboundFeature.isSSLEnabled(channel.pipeline()), 
                        subscriber);
                    return RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
                        .call(channel.connect(address));
                }})
            .subscribe(subscriber);
    }

    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels = 
            new ConcurrentHashMap<>();
    private final ChannelCreator _channelCreator;
}
