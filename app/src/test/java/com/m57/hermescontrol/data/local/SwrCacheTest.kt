package com.m57.hermescontrol.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SwrCacheTest {
    @Test
    fun testCacheHitReturnsValue() {
        val cache = SwrCache<String, String>()
        cache.put("k1", "v1")
        assertEquals("v1", cache.get("k1"))
    }

    @Test
    fun testCacheMissReturnsNull() {
        val cache = SwrCache<String, String>()
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun testTtlExpiration() {
        var currentTime = 1000L
        val cache =
            SwrCache<String, String>(
                ttlMillis = 500L,
                timeProvider = { currentTime },
            )

        cache.put("k1", "v1")
        assertEquals("v1", cache.get("k1"))

        // Advance past TTL
        currentTime = 1600L
        assertNull(cache.get("k1"))
        assertEquals(0, cache.size)
    }

    @Test
    fun testLruEviction() {
        val cache = SwrCache<String, String>(maxCapacity = 2)
        cache.put("k1", "v1")
        cache.put("k2", "v2")

        // Touch k1 to update access order so k2 becomes the eldest (LRU).
        // Under FIFO, touching k1 has no effect and k1 would be evicted on inserting k3.
        // Under LRU, k1 was recently accessed, so k2 is evicted on inserting k3.
        assertEquals("v1", cache.get("k1"))

        cache.put("k3", "v3")

        // k2 must have been evicted, while k1 and k3 are retained
        assertEquals("v1", cache.get("k1"))
        assertNull(cache.get("k2"))
        assertEquals("v3", cache.get("k3"))
        assertEquals(2, cache.size)
    }

    @Test
    fun testClearAndRemove() {
        val cache = SwrCache<String, String>()
        cache.put("k1", "v1")
        cache.put("k2", "v2")

        val removed = cache.remove("k1")
        assertEquals("v1", removed)
        assertNull(cache.get("k1"))
        assertEquals(1, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("k2"))
    }

    @Test
    fun testThreadSafetyConcurrentAccess() {
        val maxCapacity = 100
        val cache = SwrCache<String, Int>(maxCapacity = maxCapacity)
        val threadCount = 10
        val operationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)

        try {
            val futures =
                (0 until threadCount).map { threadIdx ->
                    executor.submit {
                        ready.countDown()
                        assertTrue("Workers must start together", ready.await(5, TimeUnit.SECONDS))
                        for (i in 0 until operationsPerThread) {
                            val key = "k-$threadIdx-$i"
                            cache.put(key, i)
                            val retrieved = cache.get(key)
                            // If retrieved before eviction, value must match exactly what was written
                            if (retrieved != null) {
                                assertEquals("Retrieved value must match written value for key $key", i, retrieved)
                            }
                            if (i % 5 == 0) {
                                val removed = cache.remove(key)
                                if (removed != null) {
                                    assertEquals("Removed value must match written value for key $key", i, removed)
                                }
                            }
                        }
                    }
                }

            // Propagate worker failures to JUnit and bound waiting
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            // Deterministic invariants on the cache post-concurrency:
            // 1. Size must not exceed maxCapacity
            assertTrue("Cache size ${cache.size} must not exceed capacity $maxCapacity", cache.size <= maxCapacity)
            assertTrue("Concurrent writes must leave retained entries", cache.size > 0)
            // A deterministic round trip also rejects implementations that silently drop all writes.
            cache.put("after-workers", -1)
            assertEquals(-1, cache.get("after-workers"))
        } finally {
            executor.shutdownNow()
            assertTrue(
                "Executor termination must complete within bound",
                executor.awaitTermination(5, TimeUnit.SECONDS),
            )
        }
    }
}
