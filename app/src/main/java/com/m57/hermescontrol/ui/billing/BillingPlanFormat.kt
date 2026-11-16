package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** True when the user is on a paid tier. A missing tier id falls back to the tier name. */
internal fun SubscriptionCurrent.isPaidPlan(): Boolean {
    val tier = (tier_id ?: tier_name)?.trim().orEmpty()
    return tier.isNotEmpty() && !tier.equals("free", ignoreCase = true)
}

/** Renew date to show: only on a paid plan, and only when the timestamp parses. */
internal fun SubscriptionCurrent.renewDateLabel(
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = if (isPaidPlan()) formatCycleEnd(cycle_ends_at, locale, zone) else null

/** Formats an ISO-8601 instant as a medium local date, or null when blank/unparseable. */
internal fun formatCycleEnd(
    iso: String?,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (iso.isNullOrBlank()) return null
    val instant = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: return null
    return DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zone)
        .format(instant)
}
