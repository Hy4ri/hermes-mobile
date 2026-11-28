package com.m57.hermescontrol.ui.channels

import com.m57.hermescontrol.data.model.MessagingPlatformUpdateResponse

/** Whether the running gateway applied a save itself. `live` wins; older gateways only send `hot_served`. */
internal fun appliedLive(result: MessagingPlatformUpdateResponse): Boolean = result.live?.applied ?: result.hotServed

/** Enabled but missing credentials: a restart would not start it, so the card says needs setup. */
internal fun needsCredentials(result: MessagingPlatformUpdateResponse): Boolean = result.live?.state == "not_configured"

/** Card state right after an enable/disable toggle, before the next status read. */
internal fun optimisticToggleState(
    enabled: Boolean,
    configured: Boolean,
    result: MessagingPlatformUpdateResponse,
): String {
    if (!enabled) return "disabled"
    if (!configured || needsCredentials(result)) return "not_configured"
    return if (appliedLive(result)) "connecting" else "pending_restart"
}

/** Platforms whose latest save the running gateway could not apply. */
internal fun restartPendingAfter(
    pending: Set<String>,
    platformId: String,
    result: MessagingPlatformUpdateResponse,
): Set<String> {
    val next = pending.toMutableSet()
    if (appliedLive(result)) {
        next.remove(platformId)
    } else {
        next.add(platformId)
    }
    return next
}

/** Toast line for a save the gateway could not apply live, including its reason when it gave one. */
internal fun restartReason(
    result: MessagingPlatformUpdateResponse,
    fallback: String,
): String {
    val detail = if (result.live?.applied == false) result.live.detail else null
    return if (!detail.isNullOrBlank()) "$fallback ($detail)" else fallback
}
