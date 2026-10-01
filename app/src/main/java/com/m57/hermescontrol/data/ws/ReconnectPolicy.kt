package com.m57.hermescontrol.data.ws

internal class ReconnectPolicy(
    private val random: () -> Double = { Math.random() },
) {
    @Volatile
    var currentBackoff: Long = INITIAL_BACKOFF_MS
    private var initialBackoffMs: Long = INITIAL_BACKOFF_MS
    private var testBackoffOverrideMs: Long? = null

    fun nextDelayMs(): Long {
        val override = testBackoffOverrideMs
        if (override != null) return override
        val ceiling = currentBackoff
        if (ceiling <= 0L) return 0L
        return (random() * ceiling).toLong().coerceAtLeast(BACKOFF_BASE_MS).coerceAtMost(ceiling)
    }

    fun advance() {
        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }

    fun reset() {
        currentBackoff = initialBackoffMs
    }

    fun setBackoffForTest(initialMillis: Long) {
        initialBackoffMs = initialMillis
        currentBackoff = initialMillis
        testBackoffOverrideMs = initialMillis
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val BACKOFF_BASE_MS = 300L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2.0
    }
}
