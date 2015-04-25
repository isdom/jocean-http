package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestChannelCreator implements ChannelCreator {

    private static final Logger LOG =
            LoggerFactory.getLogger(TestChannelCreator.class);
    
    final class TestChannel extends LocalChannel {
        
        private final AbstractUnsafe _unsafe0 = super.newUnsafe();
        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress remoteAddress,
                        SocketAddress localAddress, ChannelPromise promise) {
                    if (null != _pauseConnecting) {
                        try {
                            LOG.info("before pause connecting for channel:{}", TestChannel.this);
                            _pauseConnecting.await();
                            LOG.info("after pause connecting for channel:{}", TestChannel.this);
                        } catch (InterruptedException e) {
                        }
                    }
                    if (null!=_connectException) {
                        promise.tryFailure(_connectException);
                    }
                    else {
                        _unsafe0.connect(remoteAddress, localAddress, promise);
                    }
                }};
        }
        
        @Override
        protected void doWrite(ChannelOutboundBuffer in) throws Exception {
            if (null!=_writeException) {
                throw _writeException;
            }
            super.doWrite(in);
        }
        
        @Override
        public ChannelFuture close() {
            if ( _isClosed.compareAndSet(false, true)) {
                _closed.countDown();
            }
            return super.close();
        }
        
        public void awaitClosed() throws InterruptedException {
            _closed.await();
        }
        
        public void assertClosed() {
            if (!this._isClosed.get()) {
                throw new AssertionError("Channel Not Close");
            }
        }
        
        public void assertNotClose() {
            if (this._isClosed.get()) {
                throw new AssertionError("Channel Closed");
            }
        }
        
        private final CountDownLatch _closed = new CountDownLatch(1);
        private final AtomicBoolean _isClosed = new AtomicBoolean(false);
    }
    
    @Override
    public void close() throws IOException {
        // ignore
    }

    @Override
    public ChannelFuture newChannel() {
        final ChannelFuture future = this._bootstrap.register();
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("create new test channel: {}", future.channel());
        }
        _channels.add((TestChannel)future.channel());
        return future;
    }
    
    public List<TestChannel> getChannels() {
        return this._channels;
    }
    
    public TestChannelCreator setWriteException(final Exception e) {
        this._writeException = e;
        return this;
    }
    
    public TestChannelCreator setConnectException(final Exception e) {
        this._connectException = e;
        return this;
    }
    
    public TestChannelCreator setPauseConnecting(final CountDownLatch pauseConnecting) {
        this._pauseConnecting = pauseConnecting;
        return this;
    }
    
    private Exception _writeException = null;
    private Exception _connectException = null;
    private CountDownLatch _pauseConnecting = null;
    
    private final Bootstrap _bootstrap = new Bootstrap()
        .group(new LocalEventLoopGroup(1))
        .channelFactory(new ChannelFactory<TestChannel>() {
                    @Override
                    public TestChannel newChannel() {
                        return new TestChannel();
                    }})
        .handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel channel) throws Exception {
                LOG.info("processing initChannel for {}", channel);
            }});
    
    private final List<TestChannel> _channels = new ArrayList<>();
}
