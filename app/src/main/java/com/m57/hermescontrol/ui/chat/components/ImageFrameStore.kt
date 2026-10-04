package com.m57.hermescontrol.ui.chat.components

/**
 * #1459: the aspect ratio chosen for a logical chat image, keyed by message + attachment slot (never by URL,
 * which changes on handoff and token refresh). Once chosen it is never changed, so loading, failure, retry,
 * a local-to-gateway source swap and lazy-list disposal all reuse one rectangle. Holds ratios only, never
 * bitmaps; bounded so a very long session cannot grow it without limit.
 */
internal object ImageFrameStore {
    const val FALLBACK_RATIO = 4f / 3f
    private const val MAX_ENTRIES = 512

    private val ratios =
        object : LinkedHashMap<String, Float>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > MAX_ENTRIES
        }

    @Synchronized
    fun get(key: String): Float? = ratios[key]

    /** First writer wins: an established frame is never replaced. */
    @Synchronized
    fun establish(
        key: String,
        ratio: Float,
    ): Float {
        ratios[key]?.let { return it }
        val safe = if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.25f, 4f) else FALLBACK_RATIO
        ratios[key] = safe
        return safe
    }

    @Synchronized
    fun clearForTest() = ratios.clear()
}
