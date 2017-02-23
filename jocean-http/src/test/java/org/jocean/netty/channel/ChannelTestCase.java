package org.jocean.netty.channel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class ChannelTestCase {

    @Test
    public final void testChannelInactiveForInactiveChannel() throws InterruptedException {
        final EmbeddedChannel channel = new EmbeddedChannel();
        
        channel.disconnect().syncUninterruptibly();
        
        assertTrue(!channel.isActive());
        
        final AtomicBoolean isInactiveCalled = new AtomicBoolean(false);
        
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                isInactiveCalled.set(true);
            }
        });
        
        Thread.currentThread().sleep(1000);
        assertFalse(isInactiveCalled.get());
    }

}
