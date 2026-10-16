package com.m57.hermescontrol.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `default off and cold start state`() {
        val defaultPolicy = AppLockPolicy()
        assertFalse(defaultPolicy.enabled)
        assertEquals(30_000L, defaultPolicy.timeoutMs)
        assertFalse(defaultPolicy.unlocked)
        assertFalse(defaultPolicy.covered)
        assertNull(defaultPolicy.backgroundAt)
        assertEquals(0L, defaultPolicy.generation)
        assertFalse(defaultPolicy.blocked)

        val enabledColdStart = AppLockPolicy(enabled = true)
        assertFalse(enabledColdStart.unlocked)
        assertFalse(enabledColdStart.covered)
        assertTrue(enabledColdStart.blocked)
    }

    @Test
    fun `configure enable transition locks immediately`() {
        val policy = AppLockPolicy(enabled = false, unlocked = true)
        val configured = policy.configure(enabled = true, timeoutMs = 30_000L)

        assertTrue(configured.enabled)
        assertFalse(configured.unlocked)
        assertTrue(configured.blocked)
    }

    @Test
    fun `configure unchanged enabled keeps authorization`() {
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = false,
                generation = 42L,
            )
        val configured = policy.configure(enabled = true, timeoutMs = 60_000L)

        assertTrue(configured.enabled)
        assertEquals(60_000L, configured.timeoutMs)
        assertTrue(configured.unlocked)
        assertFalse(configured.blocked)
        assertEquals(42L, configured.generation)
    }

    @Test
    fun `configure disable clears blocked state`() {
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = true,
            )
        assertTrue(policy.blocked)

        val configured = policy.configure(enabled = false, timeoutMs = 30_000L)
        assertFalse(configured.enabled)
        assertFalse(configured.blocked)
    }

    @Test
    fun `configure normalizes non-whitelisted timeouts to 30000`() {
        val base = AppLockPolicy()
        assertEquals(0L, base.configure(enabled = true, timeoutMs = 0L).timeoutMs)
        assertEquals(30_000L, base.configure(enabled = true, timeoutMs = 30_000L).timeoutMs)
        assertEquals(60_000L, base.configure(enabled = true, timeoutMs = 60_000L).timeoutMs)
        assertEquals(300_000L, base.configure(enabled = true, timeoutMs = 300_000L).timeoutMs)

        assertEquals(30_000L, base.configure(enabled = true, timeoutMs = -1L).timeoutMs)
        assertEquals(30_000L, base.configure(enabled = true, timeoutMs = 15_000L).timeoutMs)
        assertEquals(30_000L, base.configure(enabled = true, timeoutMs = 45_000L).timeoutMs)
        assertEquals(30_000L, base.configure(enabled = true, timeoutMs = 120_000L).timeoutMs)
    }

    @Test
    fun `configure timeout change while enabled does not grant authorization or clear background timestamp`() {
        val lockedWithBg =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = false,
                backgroundAt = 10_000L,
            )
        val updated = lockedWithBg.configure(enabled = true, timeoutMs = 60_000L)
        assertFalse(updated.unlocked)
        assertTrue(updated.blocked)
        assertEquals(10_000L, updated.backgroundAt)
    }

    @Test
    fun `authenticated with matching generation sets unlocked true and clears background timestamp`() {
        val currentGen = 7L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = true,
                backgroundAt = 5_000L,
                generation = currentGen,
            )
        val authResult = policy.authenticated(expectedGeneration = currentGen)

        assertTrue(authResult.unlocked)
        assertNull(authResult.backgroundAt)
        assertTrue(authResult.covered)
        assertTrue(authResult.blocked)
    }

    @Test
    fun `authenticated with stale generation is rejected`() {
        val currentGen = 10L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = true,
                backgroundAt = 5_000L,
                generation = currentGen,
            )

        val authResultStale = policy.authenticated(expectedGeneration = 9L)
        assertFalse(authResultStale.unlocked)
        assertEquals(5_000L, authResultStale.backgroundAt)
        assertTrue(authResultStale.blocked)

        val authResultFuture = policy.authenticated(expectedGeneration = 11L)
        assertFalse(authResultFuture.unlocked)
        assertEquals(5_000L, authResultFuture.backgroundAt)
        assertTrue(authResultFuture.blocked)
    }

    @Test
    fun `cover and resume rotation without background does not lock or timeout`() {
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = false,
                backgroundAt = null,
                generation = 1L,
            )

        val pausedCovered = policy.cover()
        assertTrue(pausedCovered.covered)
        assertTrue(pausedCovered.unlocked)
        assertNull(pausedCovered.backgroundAt)
        assertTrue(pausedCovered.blocked)

        val resumed = pausedCovered.foreground(nowMs = 1_000L, deviceSecure = true)
        assertFalse(resumed.covered)
        assertTrue(resumed.unlocked)
        assertNull(resumed.backgroundAt)
        assertFalse(resumed.blocked)
    }

    @Test
    fun `foreground within grace boundary keeps unlocked`() {
        val bgTime = 100_000L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = true,
                backgroundAt = bgTime,
                generation = 5L,
            )

        val resumedWithinGrace = policy.foreground(nowMs = bgTime + 29_999L, deviceSecure = true)
        assertTrue(resumedWithinGrace.unlocked)
        assertFalse(resumedWithinGrace.covered)
        assertNull(resumedWithinGrace.backgroundAt)
        assertFalse(resumedWithinGrace.blocked)
    }

    @Test
    fun `foreground at exactly timeout boundary expires and locks`() {
        val bgTime = 100_000L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = true,
                backgroundAt = bgTime,
                generation = 5L,
            )

        val resumedAtBoundary = policy.foreground(nowMs = bgTime + 30_000L, deviceSecure = true)
        assertFalse(resumedAtBoundary.unlocked)
        assertFalse(resumedAtBoundary.covered)
        assertNull(resumedAtBoundary.backgroundAt)
        assertTrue(resumedAtBoundary.blocked)
    }

    @Test
    fun `foreground after timeout expires and locks`() {
        val bgTime = 100_000L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = true,
                backgroundAt = bgTime,
                generation = 5L,
            )

        val resumedPastExpiry = policy.foreground(nowMs = bgTime + 45_000L, deviceSecure = true)
        assertFalse(resumedPastExpiry.unlocked)
        assertFalse(resumedPastExpiry.covered)
        assertNull(resumedPastExpiry.backgroundAt)
        assertTrue(resumedPastExpiry.blocked)
    }

    @Test
    fun `immediate timeout locks on background and foreground`() {
        val initial =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 0L,
                unlocked = true,
                covered = false,
                generation = 3L,
            )

        val bg = initial.background(nowMs = 50_000L)
        assertTrue(bg.covered)
        assertFalse(bg.unlocked)
        assertNotEquals(3L, bg.generation)
        assertTrue(bg.blocked)

        val fg = bg.foreground(nowMs = 50_000L, deviceSecure = true)
        assertFalse(fg.unlocked)
        assertFalse(fg.covered)
        assertNull(fg.backgroundAt)
        assertTrue(fg.blocked)
    }

    @Test
    fun `background does not overwrite existing background timestamp`() {
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = false,
                backgroundAt = null,
            )

        val firstBg = policy.background(nowMs = 10_000L)
        assertEquals(10_000L, firstBg.backgroundAt)
        assertTrue(firstBg.covered)

        val secondBg = firstBg.background(nowMs = 20_000L)
        assertEquals(10_000L, secondBg.backgroundAt)
    }

    @Test
    fun `clock rollback fails closed when background interval exists`() {
        val bgTime = 100_000L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = true,
                backgroundAt = bgTime,
                generation = 2L,
            )

        val resumedWithRollback = policy.foreground(nowMs = bgTime - 1_000L, deviceSecure = true)
        assertFalse(resumedWithRollback.unlocked)
        assertFalse(resumedWithRollback.covered)
        assertNull(resumedWithRollback.backgroundAt)
        assertTrue(resumedWithRollback.blocked)
    }

    @Test
    fun `missing security locks even within grace period`() {
        val bgTime = 100_000L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = true,
                backgroundAt = bgTime,
                generation = 2L,
            )

        val resumedInsecure = policy.foreground(nowMs = bgTime + 1_000L, deviceSecure = false)
        assertFalse(resumedInsecure.unlocked)
        assertFalse(resumedInsecure.covered)
        assertNull(resumedInsecure.backgroundAt)
        assertTrue(resumedInsecure.blocked)
    }

    @Test
    fun `foreground does not grant cold start authorization`() {
        val coldStartPolicy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = true,
                backgroundAt = null,
            )

        val fg = coldStartPolicy.foreground(nowMs = 50_000L, deviceSecure = true)
        assertFalse(fg.unlocked)
        assertFalse(fg.covered)
        assertNull(fg.backgroundAt)
        assertTrue(fg.blocked)
    }

    @Test
    fun `screenOff locks covers and invalidates generation when enabled`() {
        val gen = 15L
        val policy =
            AppLockPolicy(
                enabled = true,
                timeoutMs = 30_000L,
                unlocked = true,
                covered = false,
                generation = gen,
            )

        val turnedOff = policy.screenOff()
        assertFalse(turnedOff.unlocked)
        assertTrue(turnedOff.covered)
        assertNotEquals(gen, turnedOff.generation)
        assertTrue(turnedOff.blocked)
    }

    @Test
    fun `screenOff is ignored when disabled`() {
        val policy =
            AppLockPolicy(
                enabled = false,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = false,
                generation = 15L,
            )

        val turnedOff = policy.screenOff()
        assertEquals(policy, turnedOff)
        assertFalse(turnedOff.blocked)
    }

    @Test
    fun `disabled policy never blocks across lifecycle events`() {
        val disabled =
            AppLockPolicy(
                enabled = false,
                timeoutMs = 30_000L,
                unlocked = false,
                covered = false,
            )
        assertFalse(disabled.blocked)

        val covered = disabled.cover()
        assertFalse(covered.blocked)

        val bg = covered.background(nowMs = 10_000L)
        assertFalse(bg.blocked)

        val fg = bg.foreground(nowMs = 500_000L, deviceSecure = false)
        assertFalse(fg.blocked)

        val screenOff = fg.screenOff()
        assertFalse(screenOff.blocked)
    }
}
