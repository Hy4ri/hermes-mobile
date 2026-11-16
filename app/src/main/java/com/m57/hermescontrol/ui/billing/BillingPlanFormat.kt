package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionTier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * True when the user is on a paid tier. The wire `tier_id` is an opaque id (the Free tier is a
 * cuid, paid tiers are slugs), so it cannot be compared to "free". The catalog entry for the
 * current tier carries the price (`dollars_per_month_display`, "$0" for Free); the tier name
 * is the fallback when the catalog is missing.
 */
internal fun SubscriptionCurrent.isPaidPlan(tiers: List<SubscriptionTier> = emptyList()): Boolean {
    if (tier_name?.trim().equals("free", ignoreCase = true)) return false
    val catalogEntry =
        tiers.firstOrNull { tier_id != null && it.tier_id == tier_id } ?: tiers.firstOrNull { it.is_current == true }
    val price = catalogEntry?.dollars_per_month_display?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
    if (price != null) return price > 0.0
    return !(tier_id ?: tier_name).isNullOrBlank()
}

/** Renew date to show: only on a paid plan, and only when the timestamp parses. */
internal fun SubscriptionCurrent.renewDateLabel(
    tiers: List<SubscriptionTier> = emptyList(),
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = if (isPaidPlan(tiers)) formatCycleEnd(cycle_ends_at, locale, zone) else null

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
