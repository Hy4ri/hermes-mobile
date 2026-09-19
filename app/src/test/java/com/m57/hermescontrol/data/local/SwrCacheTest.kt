package com.m57.hermescontrol.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        cache.put("k3", "v3")

        // k1 should have been evicted
        assertNull(cache.get("k1"))
        assertEquals("v2", cache.get("k2"))
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
        val cache = SwrCache<String, Int>(maxCapacity = 100)
        val threads =
            List(10) { threadIdx ->
                Thread {
                    for (i in 0 until 500) {
                        cache.put("k-$threadIdx-$i", i)
                        cache.get("k-$threadIdx-$i")
                        if (i % 5 == 0) cache.remove("k-$threadIdx-$i")
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
