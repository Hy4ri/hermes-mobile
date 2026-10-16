package com.m57.hermescontrol.applock

/** Monotonic-time UI privacy policy; authorization is deliberately never persisted (#1604). */
internal data class AppLockPolicy(
    val enabled: Boolean = false,
    val timeoutMs: Long = 30_000L,
    val unlocked: Boolean = false,
    val covered: Boolean = false,
    val backgroundAt: Long? = null,
    val generation: Long = 0L,
) {
    val blocked: Boolean get() = enabled && (!unlocked || covered)

    fun configure(
        enabled: Boolean,
        timeoutMs: Long,
    ): AppLockPolicy {
        val timeout = timeoutMs.takeIf { it in TIMEOUTS } ?: 30_000L
        if (enabled == this.enabled) return copy(timeoutMs = timeout)
        return AppLockPolicy(enabled = enabled, timeoutMs = timeout, generation = generation + 1)
    }

    fun cover(): AppLockPolicy = if (enabled) copy(covered = true) else this

    fun background(nowMs: Long): AppLockPolicy {
        if (!enabled) return this
        val next = copy(covered = true, backgroundAt = backgroundAt ?: nowMs)
        return if (timeoutMs == 0L) next.copy(unlocked = false, generation = generation + 1) else next
    }

    fun foreground(
        nowMs: Long,
        deviceSecure: Boolean,
    ): AppLockPolicy {
        if (!enabled) return this
        val elapsed = backgroundAt?.let { nowMs - it }
        val expired = elapsed != null && (elapsed < 0 || elapsed >= timeoutMs)
        val revoke = !deviceSecure || expired
        return copy(
            covered = false,
            backgroundAt = null,
            unlocked = unlocked && !revoke,
            generation = if (revoke) generation + 1 else generation,
        )
    }

    fun screenOff(): AppLockPolicy =
        if (enabled) copy(unlocked = false, covered = true, generation = generation + 1) else this

    fun authenticated(expectedGeneration: Long): AppLockPolicy =
        if (enabled && expectedGeneration == generation) copy(unlocked = true, backgroundAt = null) else this

    companion object {
        val TIMEOUTS = listOf(0L, 30_000L, 60_000L, 300_000L)
    }
}
