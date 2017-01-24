package org.jocean.http.util;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.PoolArenaMetric;
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
        metrics.put("numThreadCaches", poolArenaMetric.numThreadCaches());

        /**
         * Returns the number of tiny sub-pages for the arena.
         */
        metrics.put("numTinySubpages", poolArenaMetric.numTinySubpages());

        /**
         * Returns the number of small sub-pages for the arena.
         */
        metrics.put("numSmallSubpages", poolArenaMetric.numSmallSubpages());

        /**
         * Returns the number of chunk lists for the arena.
         */
        metrics.put("numChunkLists", poolArenaMetric.numChunkLists());

        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for tiny sub-pages.
         */
        {
            int idx = 0;
            for (PoolSubpageMetric subpageMetric :  poolArenaMetric.tinySubpages()) {
                metrics.put("tinySubpage[" + idx++ + "]", metricsOfPoolSubpage(subpageMetric));
            }
        }

        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolSubpageMetric}s for small sub-pages.
         */
        {
            int idx = 0;
            for (PoolSubpageMetric subpageMetric :  poolArenaMetric.smallSubpages()) {
                metrics.put("smallSubpage[" + idx++ + "]", metricsOfPoolSubpage(subpageMetric));
            }
        }

        /**
         * Returns an unmodifiable {@link List} which holds {@link PoolChunkListMetric}s.
         */
//        List<PoolChunkListMetric> chunkLists();

        /**
         * Return the number of allocations done via the arena. This includes all sizes.
         */
        metrics.put("numAllocations", poolArenaMetric.numAllocations());

        /**
         * Return the number of tiny allocations done via the arena.
         */
        metrics.put("numTinyAllocations", poolArenaMetric.numTinyAllocations());

        /**
         * Return the number of small allocations done via the arena.
         */
        metrics.put("numSmallAllocations", poolArenaMetric.numSmallAllocations());

        /**
         * Return the number of normal allocations done via the arena.
         */
        metrics.put("numNormalAllocations", poolArenaMetric.numNormalAllocations());

        /**
         * Return the number of huge allocations done via the arena.
         */
        metrics.put("numHugeAllocations", poolArenaMetric.numHugeAllocations());

        /**
         * Return the number of deallocations done via the arena. This includes all sizes.
         */
        metrics.put("numDeallocations", poolArenaMetric.numDeallocations());

        /**
         * Return the number of tiny deallocations done via the arena.
         */
        metrics.put("numTinyDeallocations", poolArenaMetric.numTinyDeallocations());

        /**
         * Return the number of small deallocations done via the arena.
         */
        metrics.put("numSmallDeallocations", poolArenaMetric.numSmallDeallocations());

        /**
         * Return the number of normal deallocations done via the arena.
         */
        metrics.put("numNormalDeallocations", poolArenaMetric.numNormalDeallocations());

        /**
         * Return the number of huge deallocations done via the arena.
         */
        metrics.put("numHugeDeallocations", poolArenaMetric.numHugeDeallocations());

        /**
         * Return the number of currently active allocations.
         */
        metrics.put("numActiveAllocations", poolArenaMetric.numActiveAllocations());

        /**
         * Return the number of currently active tiny allocations.
         */
        metrics.put("numActiveTinyAllocations", poolArenaMetric.numActiveTinyAllocations());

        /**
         * Return the number of currently active small allocations.
         */
        metrics.put("numActiveSmallAllocations", poolArenaMetric.numActiveSmallAllocations());

        /**
         * Return the number of currently active normal allocations.
         */
        metrics.put("numActiveNormalAllocations", poolArenaMetric.numActiveNormalAllocations());

        /**
         * Return the number of currently active huge allocations.
         */
        metrics.put("numActiveHugeAllocations", poolArenaMetric.numActiveHugeAllocations());

        /**
         * Return the number of active bytes that are currently allocated by the arena.
         */
        metrics.put("numActiveBytes", poolArenaMetric.numActiveBytes());
        
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
