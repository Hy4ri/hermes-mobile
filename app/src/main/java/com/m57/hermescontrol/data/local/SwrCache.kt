package com.m57.hermescontrol.data.local

import java.util.Collections

/**
 * Lightweight thread-safe in-memory cache for Stale-While-Revalidate screen state.
 * Stores entries with optional TTL and a bounded capacity.
 */
class SwrCache<K : Any, V : Any>(
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry<V>(
        val value: V,
        val timestamp: Long,
    )

    private val map: MutableMap<K, Entry<V>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<K, Entry<V>>(maxCapacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
                    size > maxCapacity
            },
        )

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (ttlMillis > 0 && timeProvider() - entry.timestamp > ttlMillis) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(
        key: K,
        value: V,
    ) {
        map[key] = Entry(value, timeProvider())
    }

    fun remove(key: K): V? = map.remove(key)?.value

    fun clear() {
        map.clear()
    }

    val size: Int get() = map.size

    companion object {
        const val DEFAULT_MAX_CAPACITY = 20
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}
