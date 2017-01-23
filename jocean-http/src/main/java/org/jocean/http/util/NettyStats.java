package org.jocean.http.util;

import io.netty.buffer.PooledByteBufAllocator;

public class NettyStats {
    public String getPooledByteBufAllocatorStats() {
        return PooledByteBufAllocator.DEFAULT.dumpStats();
    }
}
