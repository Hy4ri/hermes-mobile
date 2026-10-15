package com.m57.hermescontrol.data.model

import java.time.Instant
import java.time.OffsetDateTime

private const val LOW_QUOTA_PERCENT = 20
private const val CRITICAL_QUOTA_PERCENT = 10
private const val MODEL_SCOPE = "model"

/** What the provider header should surface: an active cooldown or a nearly spent subscription window. */
sealed interface ProviderUsageStatus {
    data class Limited(
        val resetsAt: Instant?,
    ) : ProviderUsageStatus

    data class LowQuota(
        val percentLeft: Int,
        val resetsAt: Instant?,
    ) : ProviderUsageStatus {
        val isCritical: Boolean get() = percentLeft <= CRITICAL_QUOTA_PERCENT
    }
}

/**
 * Provider-wide status for the header chip, or null when healthy.
 *
 * Model-scoped limits (`limit.scope == "models"`) and model-scoped windows are ignored here: they do not
 * make the whole provider unusable. A limit whose `resets_at` has already passed is stale and ignored.
 */
fun ModelProvider.usageStatus(now: Instant = Instant.now()): ProviderUsageStatus? {
    val activeLimit = limit?.takeIf { it.isProviderWide() }
    if (activeLimit != null) {
        val resetsAt = parseInstantOrNull(activeLimit.resets_at)
        if (resetsAt == null || resetsAt.isAfter(now)) return ProviderUsageStatus.Limited(resetsAt)
    }
    val tightest =
        usage
            ?.windows
            .orEmpty()
            .filter { it.used_percent != null && it.scope != MODEL_SCOPE }
            .filter { window -> parseInstantOrNull(window.resets_at)?.isAfter(now) != false }
            .minByOrNull { 100.0 - (it.used_percent ?: 0.0) }
            ?: return null
    val left = (100.0 - (tightest.used_percent ?: 0.0)).coerceIn(0.0, 100.0)
    val percentLeft = Math.round(left).toInt()
    if (percentLeft > LOW_QUOTA_PERCENT) return null
    return ProviderUsageStatus.LowQuota(percentLeft, parseInstantOrNull(tightest.resets_at))
}

private fun ProviderLimit.isProviderWide(): Boolean = scope != "models" || models.isNullOrEmpty()

internal fun parseInstantOrNull(value: String?): Instant? =
    value?.takeIf { it.isNotBlank() }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
