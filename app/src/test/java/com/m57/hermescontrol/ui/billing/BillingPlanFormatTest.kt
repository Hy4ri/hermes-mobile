package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class BillingPlanFormatTest {
    private val iso = "2026-12-03T17:54:44.000Z"

    @Test
    fun formatCycleEnd_usesMediumLocalDate_notRawIso() {
        assertEquals("Dec 3, 2026", formatCycleEnd(iso, Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun formatCycleEnd_respectsTimezoneDayBoundary() {
        assertEquals("Dec 4, 2026", formatCycleEnd(iso, Locale.US, ZoneId.of("Pacific/Auckland")))
    }

    @Test
    fun formatCycleEnd_blankOrGarbage_isNull() {
        assertNull(formatCycleEnd(null))
        assertNull(formatCycleEnd(" "))
        assertNull(formatCycleEnd("not a date"))
    }

    // Real `subscription.state` payload shape from the live gateway: Free has an opaque cuid id.
    private val tiers =
        listOf(
            SubscriptionTier(
                tier_id = "cmnie0xix000bjp049kwwgwgz",
                name = "Free",
                dollars_per_month_display = "$0",
                is_current = true,
            ),
            SubscriptionTier(tier_id = "usage-window-hermes-plus", name = "Plus", dollars_per_month_display = "$20"),
        )
    private val realFree =
        SubscriptionCurrent(tier_id = "cmnie0xix000bjp049kwwgwgz", tier_name = "Free", cycle_ends_at = iso)

    @Test
    fun realFreePayload_isNotPaid_andHidesRenewDate() {
        assertFalse(realFree.isPaidPlan(tiers))
        assertNull(realFree.renewDateLabel(tiers, Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun freeDetectedByZeroPrice_evenWithOddName() {
        val cur = SubscriptionCurrent(tier_id = "cmnie0xix000bjp049kwwgwgz", tier_name = "Starter", cycle_ends_at = iso)
        assertFalse(cur.isPaidPlan(tiers))
    }

    @Test
    fun paidPlan_showsRenewDate() {
        val plus = SubscriptionCurrent(tier_id = "usage-window-hermes-plus", tier_name = "Plus", cycle_ends_at = iso)
        assertTrue(plus.isPaidPlan(tiers))
        assertEquals("Dec 3, 2026", plus.renewDateLabel(tiers, Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun missingCatalog_fallsBackToName() {
        assertFalse(SubscriptionCurrent(tier_id = "x", tier_name = "FREE").isPaidPlan())
        assertTrue(SubscriptionCurrent(tier_id = "x", tier_name = "Plus").isPaidPlan())
    }
}
