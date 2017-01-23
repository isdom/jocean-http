package org.jocean.http.util;

import io.netty.buffer.PooledByteBufAllocator;

public class NettyMBean {
    public String getPooledByteBufAllocatorStats() {
        return PooledByteBufAllocator.DEFAULT.dumpStats();
    }
}
