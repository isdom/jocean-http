package org.jocean.http.client.impl;

import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;

public class TestChannelPool extends DefaultChannelPool {

    public TestChannelPool(final ChannelCreator channelCreator, final int recycleChannelCount) {
        super(channelCreator);
        this._countdown = new CountDownLatch(recycleChannelCount);
    }

    @Override
    public boolean recycleChannel(final SocketAddress address, final Channel channel) {
        final boolean ret = super.recycleChannel(address, channel);
        this._countdown.countDown();
        return ret;
    }
    
    public void awaitRecycleChannels() throws InterruptedException {
        this._countdown.await();
    }

    private final CountDownLatch _countdown;
}
