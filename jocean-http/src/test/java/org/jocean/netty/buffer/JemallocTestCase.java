package org.jocean.netty.buffer;

import static org.junit.Assert.*;

import org.junit.Test;

public class JemallocTestCase {
    final static int chunkSize = 8192 << 11;
    
    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }
    
    static int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }
        if (reqCapacity >= chunkSize) {
            return reqCapacity;
        }

        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }

            return normalizedCapacity;
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }
    
    @Test
    public final void testNormalizeCapacity() {
        // test for tiny size < 512
        assertEquals(16, normalizeCapacity(1));
        assertEquals(16, normalizeCapacity(16));
        assertEquals(32, normalizeCapacity(17));
        assertEquals(32, normalizeCapacity(32));
        assertEquals(48, normalizeCapacity(33));
        assertEquals(48, normalizeCapacity(48));
        assertEquals(512, normalizeCapacity(511));
        
        // test for small size >=512
        assertEquals(512, normalizeCapacity(512));
        assertEquals(1024, normalizeCapacity(513));
        assertEquals(1024, normalizeCapacity(1024));
        assertEquals(2048, normalizeCapacity(1025));
        assertEquals(2048, normalizeCapacity(2048));
        assertEquals(8192, normalizeCapacity(8192));
        assertEquals(16384, normalizeCapacity(8193));
    }

}
