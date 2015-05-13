package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jocean.http.client.OutboundFeature;
import org.jocean.http.util.HandlersClosure;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;

public abstract class AbstractChannelPool implements ChannelPool {

    protected AbstractChannelPool(final ChannelCreator channelCreator) {
        this._channelCreator = channelCreator;
    }
    
    @Override
    public Observable<? extends Channel> retainChannel(
            final SocketAddress address, 
            final OutboundFeature.Applicable[] features) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        final Channel channel = reuseChannel(address);
                        if (null!=channel) {
                            final Runnable runnable = buildOnNextRunnable(subscriber, channel, features);
                            if (channel.eventLoop().inEventLoop()) {
                                runnable.run();
                            } else {
                                RxNettys.<Future<?>,Channel>emitErrorOnFailure()
                                    .call(channel.eventLoop().submit(runnable))
                                    .subscribe(subscriber);
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
    
    private Runnable buildOnNextRunnable(
            final Subscriber<? super Channel> subscriber,
            final Channel channel, 
            final OutboundFeature.Applicable[] features) {
        return new Runnable() {
            @Override
            public void run() {
                subscriber.add(addOneoffFeatures(channel, features));
                subscriber.onNext(channel);
                subscriber.onCompleted();
            }};
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
                        if (!applicable.isOneoff()) {
                            applicable.call(channel);
                        }
                    }
                    subscriber.add(addOneoffFeatures(channel, features));
                    
                    OutboundFeature.CONNECTING_NOTIFIER.applyTo(
                        channel, 
                        OutboundFeature.isSSLEnabled(channel.pipeline()), 
                        subscriber);
                    
                    return RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
                        .call(channel.connect(address));
                }})
            .subscribe(subscriber);
    }
    
    private Subscription addOneoffFeatures(
            final Channel channel,
            final OutboundFeature.Applicable[] features) {
        final HandlersClosure closure = 
                Nettys.channelHandlersClosure(channel);
        for (OutboundFeature.Applicable applicable : features) {
            if (applicable.isOneoff()) {
                closure.call(applicable.call(channel));
            }
        }
        return channelClosure(channel, closure);
    }

    private Subscription channelClosure(
            final Channel channel, 
            final HandlersClosure closure) {
        final AtomicBoolean isUnsubscribed = new AtomicBoolean(false);
        return new Subscription() {
            @Override
            public void unsubscribe() {
                if (isUnsubscribed.compareAndSet(false, true)) {
                    if (channel.eventLoop().inEventLoop()) {
                        doRecycle(channel, closure);
                    } else {
                        channel.eventLoop().submit(new Runnable() {
                            @Override
                            public void run() {
                                doRecycle(channel, closure);
                            }});
                    }
                }
            }

            private void doRecycle(
                    final Channel channel,
                    final HandlersClosure closure) {
                try {
                    closure.close();
                } catch (IOException e) {
                }
                if (!channel.isActive() 
                || !recycleChannel(channel)) {
                    channel.close();
                }
            }
            
            @Override
            public boolean isUnsubscribed() {
                return isUnsubscribed.get();
            }
        };
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
    
    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels = 
            new ConcurrentHashMap<>();
    private final ChannelCreator _channelCreator;
}
