package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.util.concurrent.CountDownLatch;

public class TestChannelPool extends DefaultChannelPool {

    public TestChannelPool(final int recycleChannelCount) {
        this._countdown = new CountDownLatch(recycleChannelCount);
    }

    @Override
    public boolean recycleChannel(final Channel channel) {
        try {
            return super.recycleChannel(channel);
        } finally {
            this._countdown.countDown();
        }
    }
    
    public void awaitRecycleChannels() throws InterruptedException {
        this._countdown.await();
    }

    private final CountDownLatch _countdown;
}
