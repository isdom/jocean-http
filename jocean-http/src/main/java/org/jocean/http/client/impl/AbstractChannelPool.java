package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.SocketAddress;
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
                            final HandlersClosure closure = 
                                    Nettys.channelHandlersClosure(channel);
                            subscriber.add(recycleChannelSubscription(channel, closure));
                            final Runnable runnable = buildOnNextRunnable(subscriber, closure, channel, features);
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
            final HandlersClosure closure,
            final Channel channel, 
            final OutboundFeature.Applicable[] features) {
        return new Runnable() {
            @Override
            public void run() {
                addOneoffFeatures(closure, channel, features);
                subscriber.onNext(channel);
                subscriber.onCompleted();
            }};
    }

    private void onNextNewChannel(
            final Subscriber<? super Channel> subscriber,
            final SocketAddress address,
            final OutboundFeature.Applicable[] features) {
        final ChannelFuture future = _channelCreator.newChannel();
        final HandlersClosure closure = 
                Nettys.channelHandlersClosure(future.channel());
        subscriber.add(recycleChannelSubscription(future.channel(), closure));
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
                    addOneoffFeatures(closure, channel, features);
                    
                    OutboundFeature.READY4INTERACTION_NOTIFIER.applyTo(
                        channel, 
                        OutboundFeature.isSSLEnabled(channel.pipeline()), 
                        subscriber);
                    
                    return RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
                        .call(channel.connect(address));
                }})
            .subscribe(subscriber);
    }
    
    private void addOneoffFeatures(
            final HandlersClosure closure,
            final Channel channel,
            final OutboundFeature.Applicable[] features) {
        for (OutboundFeature.Applicable applicable : features) {
            if (applicable.isOneoff()) {
                closure.call(applicable.call(channel));
            }
        }
    }

    private Subscription recycleChannelSubscription(
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
                recycleChannel(channel);
            }
            
            @Override
            public boolean isUnsubscribed() {
                return isUnsubscribed.get();
            }
        };
    }

    protected abstract Channel reuseChannel(final SocketAddress address);
    
    private final ChannelCreator _channelCreator;
}
