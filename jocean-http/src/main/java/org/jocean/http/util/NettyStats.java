package org.jocean.http.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PoolChunkListMetric;
import io.netty.buffer.PoolChunkMetric;
import io.netty.buffer.PoolSubpageMetric;
import io.netty.buffer.PooledByteBufAllocator;

public class NettyStats {
    public Map<String, Object> getPooledByteBufAllocatorMetric() {
        final PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        final Map<String, Object> metrics = new HashMap<>();
        {
            int idx = 0;
            for (PoolArenaMetric poolArenaMetric : allocator.heapArenas()) {
                metrics.put("heap arena[" + idx++ + "]", metricsOfPoolArena(poolArenaMetric));
            }
        }
        {
            int idx = 0;
            for (PoolArenaMetric poolArenaMetric : allocator.directArenas()) {
                metrics.put("direct arena[" + idx++ + "]", metricsOfPoolArena(poolArenaMetric));
            }
        }
        
        return metrics;
    }

    private static Map<String, Object> metricsOfPoolArena(
            final PoolArenaMetric poolArenaMetric) {
        final Map<String, Object> metrics = new HashMap<>();
        
        /**
         * Returns the number of thread caches backed by this arena.
         */
        metrics.put("1_numThreadCaches", poolArenaMetric.numThreadCaches());

        /**
         * Return the number of allocations done via the arena. This includes all sizes.
         */
        metrics.put("2_0_numAllocations", poolArenaMetric.numAllocations());

        /**
         * Return the number of tiny allocations done via the arena.
         */
        metrics.put("2_1_numTinyAllocations", poolArenaMetric.numTinyAllocations());

        /**
         * Return the number of small allocations done via the arena.
         */
        metrics.put("2_2_numSmallAllocations", poolArenaMetric.numSmallAllocations());

        /**
         * Return the number of normal allocations done via the arena.
         */
        metrics.put("2_3_numNormalAllocations", poolArenaMetric.numNormalAllocations());

        /**
         * Return the number of huge allocations done via the arena.
         */
        metrics.put("2_4_numHugeAllocations", poolArenaMetric.numHugeAllocations());

        /**
         * Return the number of deallocations done via the arena. This includes all sizes.
         */
        metrics.put("3_0_numDeallocations", poolArenaMetric.numDeallocations());

        /**
         * Return the number of tiny deallocations done via the arena.
         */
        metrics.put("3_1_numTinyDeallocations", poolArenaMetric.numTinyDeallocations());

        /**
         * Return the number of small deallocations done via the arena.
         */
        metrics.put("3_2_numSmallDeallocations", poolArenaMetric.numSmallDeallocations());

        /**
         * Return the number of normal deallocations done via the arena.
         */
        metrics.put("3_3_numNormalDeallocations", poolArenaMetric.numNormalDeallocations());

        /**
         * Return the number of huge deallocations done via the arena.
         */
        metrics.put("3_4_numHugeDeallocations", poolArenaMetric.numHugeDeallocations());

        /**
         * Return the number of currently active allocations.
         */
        metrics.put("4_0_numActiveAllocations", poolArenaMetric.numActiveAllocations());

        /**
         * Return the number of currently active tiny allocations.
         */
        metrics.put("4_1_numActiveTinyAllocations", poolArenaMetric.numActiveTinyAllocations());

        /**
         * Return the number of currently active small allocations.
         */
        metrics.put("4_2_numActiveSmallAllocations", poolArenaMetric.numActiveSmallAllocations());

        /**
         * Return the number of currently active normal allocations.
         */
        metrics.put("4_3_numActiveNormalAllocations", poolArenaMetric.numActiveNormalAllocations());

        /**
         * Return the number of currently active huge allocations.
         */
        metrics.put("4_4_numActiveHugeAllocations", poolArenaMetric.numActiveHugeAllocations());

        /**
         * Return the number of active bytes that are currently allocated by the arena.
         */
        metrics.put("5_numActiveBytes", poolArenaMetric.numActiveBytes());
        
        /**
         * Returns the number of chunk lists for the arena.
         */
        metrics.put("6_0_numChunkLists", poolArenaMetric.numChunkLists());
        
        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolChunkListMetric}s.
         */
        {
            int idx = 0;
            for (PoolChunkListMetric chunkListMetric :  poolArenaMetric.chunkLists()) {
                metrics.put("6_chunkList[" + idx++ + "]", metricsOfPoolChunkList(chunkListMetric));
            }
        }
        /**
         * Returns the number of tiny sub-pages for the arena.
         */
        metrics.put("7_0_numTinySubpages", poolArenaMetric.numTinySubpages());

        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for tiny sub-pages.
         */
        {
            int idx = 0;
            for (PoolSubpageMetric subpageMetric :  poolArenaMetric.tinySubpages()) {
                metrics.put("7_tinySubpage[" + idx++ + "]", metricsOfPoolSubpage(subpageMetric));
            }
        }

        /**
         * Returns the number of small sub-pages for the arena.
         */
        metrics.put("8_0_numSmallSubpages", poolArenaMetric.numSmallSubpages());

        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for small sub-pages.
         */
        {
            int idx = 0;
            for (PoolSubpageMetric subpageMetric :  poolArenaMetric.smallSubpages()) {
                metrics.put("8_smallSubpage[" + idx++ + "]", metricsOfPoolSubpage(subpageMetric));
            }
        }
        
        return metrics;
    }

    private static Map<String, Object> metricsOfPoolChunkList(final PoolChunkListMetric chunkListMetric) {
        final Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("minUsage", chunkListMetric.minUsage());
        metrics.put("maxUsage", chunkListMetric.maxUsage());
        
        final Iterator<PoolChunkMetric> iter = chunkListMetric.iterator();
        int idx = 0;
        while (iter.hasNext()) {
            final PoolChunkMetric chunkMetric = iter.next();
            metrics.put("chunk[" + idx +"].usage", chunkMetric.usage());
            metrics.put("chunk[" + idx +"].freeBytes", chunkMetric.freeBytes());
            metrics.put("chunk[" + idx +"].chunkSize", chunkMetric.chunkSize());
            idx++;
        }
        return metrics;
    }

    private static Map<String, Object> metricsOfPoolSubpage(final PoolSubpageMetric subpageMetric) {
        final Map<String, Object> metrics = new HashMap<>();
        /**
         * Return the number of maximal elements that can be allocated out of the sub-page.
         */
        metrics.put("maxNumElements", subpageMetric.maxNumElements());

        /**
         * Return the number of available elements to be allocated.
         */
        metrics.put("numAvailable", subpageMetric.numAvailable());

        /**
         * Return the size (in bytes) of the elements that will be allocated.
         */
        metrics.put("elementSize", subpageMetric.elementSize());

        /**
         * Return the size (in bytes) of this page.
         */
        metrics.put("pageSize", subpageMetric.pageSize());

        return metrics;
    }

    public String getPooledByteBufAllocatorMetricAsString() {
        return PooledByteBufAllocator.DEFAULT.dumpStats();
    }
}
