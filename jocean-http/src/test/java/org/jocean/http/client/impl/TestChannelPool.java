package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class TestChannelPool extends DefaultChannelPool {

    public TestChannelPool(final int recycleChannelCount) {
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

    public void awaitRecycleChannelsAndReset(final int recycleChannelCount) throws InterruptedException {
        this._countdownRef.get().await();
        this._countdownRef.set(new CountDownLatch(recycleChannelCount));
    }
    
    private final AtomicReference<CountDownLatch> _countdownRef = 
            new AtomicReference<CountDownLatch>();
}
