package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One throttled streaming channel: cumulative text, last flush and trailing timer. */
internal class StreamingBuffer(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val nowMs: () -> Long,
    private val flush: (String) -> Unit,
) {
    private val content = StringBuilder()
    private var lastFlushMs = 0L
    private var trailingJob: Job? = null

    fun append(
        delta: String,
        flushImmediately: Boolean,
    ) {
        content.append(delta)
        val now = nowMs()
        if (flushImmediately || lastFlushMs == 0L || now - lastFlushMs >= intervalMs) {
            trailingJob?.cancel()
            flushNow(now)
        } else if (trailingJob?.isActive != true) {
            trailingJob =
                scope.launch {
                    delay(intervalMs)
                    flushNow(nowMs())
                }
        }
    }

    /** Keep the cumulative text: subsequent deltas append to the same message. */
    fun flushPending(override: ((String) -> Unit)? = null) {
        trailingJob?.cancel()
        flushNow(nowMs(), override)
    }

    fun clear() {
        trailingJob?.cancel()
        trailingJob = null
        content.clear()
        lastFlushMs = 0L
    }

    private fun flushNow(
        now: Long,
        override: ((String) -> Unit)? = null,
    ) {
        if (content.isEmpty()) return
        lastFlushMs = now
        (override ?: flush)(content.toString())
    }
}
