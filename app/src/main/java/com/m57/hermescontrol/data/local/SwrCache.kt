package com.m57.hermescontrol.data.local

/**
 * Lightweight thread-safe in-memory cache for Stale-While-Revalidate screen state.
 * Stores entries with optional TTL and a bounded capacity.
 *
 * All map operations are synchronized on a private lock so that compound
 * operations (e.g. read + TTL expiry check + remove) are atomic and safe against races.
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

    private val lock = Any()
    private val map =
        object : LinkedHashMap<K, Entry<V>>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean = size > maxCapacity
        }

    fun get(key: K): V? =
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (ttlMillis > 0 && timeProvider() - entry.timestamp > ttlMillis) {
                map.remove(key)
                return null
            }
            entry.value
        }

    fun put(
        key: K,
        value: V,
    ) {
        synchronized(lock) {
            map[key] = Entry(value, timeProvider())
        }
    }

    fun remove(key: K): V? =
        synchronized(lock) {
            map.remove(key)?.value
        }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { map.size }

    companion object {
        const val DEFAULT_MAX_CAPACITY = 50
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}
