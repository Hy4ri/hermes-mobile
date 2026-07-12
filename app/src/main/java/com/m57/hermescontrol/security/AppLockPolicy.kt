package com.m57.hermescontrol.security

object AppLockPolicy {
    const val DEFAULT_TIMEOUT_MS = 60_000L

    fun shouldLock(
        lastBackgroundAtMs: Long?,
        nowMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (lastBackgroundAtMs == null) return true
        val elapsed = nowMs - lastBackgroundAtMs
        return elapsed < 0L || elapsed >= timeoutMs
    }
}
