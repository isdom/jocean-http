package org.jocean.netty.buffer;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;

public class PoolArenaTestCase {

    @Test
    public final void testPoolArenaAllocationCounter() {
        final PooledByteBufAllocator allocator = new PooledByteBufAllocator(
                true,   //boolean preferDirect, 
                0,      //int nHeapArena, 
                1,      //int nDirectArena, 
                8192,   //int pageSize, 
                11,     //int maxOrder,
                0,      //int tinyCacheSize, 
                0,      //int smallCacheSize, 
                0,      //int normalCacheSize,
                true    //boolean useCacheForAllThreads
                );
        
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
        
        assertEquals(3, metric.numDeallocations());
        assertEquals(3, metric.numAllocations());
        
        assertEquals(metric.numTinyDeallocations(), metric.numTinyAllocations());
        assertEquals(metric.numSmallDeallocations(), metric.numSmallAllocations());
        assertEquals(metric.numNormalDeallocations(), metric.numNormalAllocations());
    }

}
