package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.util.HttpHandlers;

public class TestChannelPool extends DefaultChannelPool {

    public TestChannelPool(final int recycleChannelCount) {
        super(HttpHandlers.ON_CHANNEL_INACTIVE);
        this._countdownRef.set(new CountDownLatch(recycleChannelCount));
    }

    @Override
    public boolean recycleChannel(final Channel channel) {
        try {
            return super.recycleChannel(channel);
        } finally {
            this._countdownRef.get().countDown();
        }
    }
    
    public void awaitRecycleChannels() throws InterruptedException {
        this._countdownRef.get().await();
    }

    public void awaitRecycleChannels(final long timeout) throws InterruptedException {
        this._countdownRef.get().await(timeout, TimeUnit.SECONDS);
    }
    
    public void awaitRecycleChannelsAndReset(final long timeout, final int recycleChannelCount) throws InterruptedException {
        this._countdownRef.get().await(timeout, TimeUnit.SECONDS);
        this._countdownRef.set(new CountDownLatch(recycleChannelCount));
    }
    
    public void awaitRecycleChannelsAndReset(final int recycleChannelCount) throws InterruptedException {
        this._countdownRef.get().await();
        this._countdownRef.set(new CountDownLatch(recycleChannelCount));
    }
    
    private final AtomicReference<CountDownLatch> _countdownRef = 
            new AtomicReference<CountDownLatch>();
}
