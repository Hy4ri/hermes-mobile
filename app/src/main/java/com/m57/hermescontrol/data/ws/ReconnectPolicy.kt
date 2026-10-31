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

    /**
     * Advance the ceiling after a failed attempt.
     *
     * [serverNotReady] — the ticket endpoint answered `5xx`: the server is reachable but not ready
     * yet, which is exactly what a restarting gateway looks like (Caddy answers `502`/`503` while
     * the tunnel and the gateway come back). Doubling the ceiling there is what turned a ~1 s
     * re-attach into a ~20 s one — the last failed mint set the wait, and by then the gateway was
     * already serving. Such a failure pins the ceiling to [SERVER_NOT_READY_CEILING_MS] instead, so
     * retries stay a few seconds apart until the server answers; a successful open resets the
     * policy anyway.
     */
    fun advance(serverNotReady: Boolean = false) {
        currentBackoff =
            if (serverNotReady) {
                SERVER_NOT_READY_CEILING_MS
            } else {
                (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
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

        /** Short ceiling while the server is up but not ready (`5xx` from the ticket endpoint). */
        internal const val SERVER_NOT_READY_CEILING_MS = 3_000L
    }
}
