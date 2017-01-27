package org.jocean.netty.buffer;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;

public class PooledByteBufAllocatorTestCase {

    @Test
    public final void testPoolArenaAllocationCounter() {
        System.setProperty("io.netty.allocator.tinyCacheSize", "0");
        System.setProperty("io.netty.allocator.smallCacheSize", "0");
        System.setProperty("io.netty.allocator.normalCacheSize", "0");
        System.setProperty("io.netty.allocator.type", "pooled");
        
        final PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        
        // alloc tiny buf
        final ByteBuf b1 = allocator.directBuffer(24);
        
        // alloc small buf
        final ByteBuf b2 = allocator.directBuffer(800);
        
        // alloc normal buf
        final ByteBuf b3 = allocator.directBuffer(8192 * 2);
        
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotNull(b3);
        
        assertTrue(b1.release());
        assertTrue(b2.release());
        assertTrue(b3.release());
        
        assertTrue(allocator.directArenas().size() >= 1);
        
        final PoolArenaMetric metric = allocator.directArenas().get(0);
        
        assertEquals(metric.numDeallocations(), metric.numAllocations());
        assertEquals(metric.numTinyDeallocations(), metric.numTinyAllocations());
        assertEquals(metric.numSmallDeallocations(), metric.numSmallAllocations());
        assertEquals(metric.numNormalDeallocations(), metric.numNormalAllocations());
        
    }

}
