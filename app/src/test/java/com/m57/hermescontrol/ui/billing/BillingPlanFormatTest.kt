package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
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

    @Test
    fun freePlan_hidesRenewDate() {
        val free = SubscriptionCurrent(tier_id = "free", tier_name = "Free", cycle_ends_at = iso)
        assertFalse(free.isPaidPlan())
        assertNull(free.renewDateLabel(Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun freePlan_matchedByNameWhenIdMissing() {
        assertFalse(SubscriptionCurrent(tier_name = "FREE").isPaidPlan())
    }

    @Test
    fun paidPlan_showsRenewDate() {
        val plus = SubscriptionCurrent(tier_id = "plus", tier_name = "Plus", cycle_ends_at = iso)
        assertTrue(plus.isPaidPlan())
        assertEquals("Dec 3, 2026", plus.renewDateLabel(Locale.US, ZoneId.of("UTC")))
    }
}
