package com.m57.hermescontrol.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConnectionPolicyTest {
    @Test
    fun testForeground_hasNoServiceDemand() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = true,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testIneligible_winsOverAllDemand() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = true,
                isEligibleForConnection = false,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testBackground_optInOff_noPendingReply_noService() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = false,
                pendingReply = false,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testBackground_optInOff_pendingReply_holdsServiceRepliesOnly() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = false,
                pendingReply = true,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.WaitingForReplies, decision.notificationState)
    }

    @Test
    fun testBackground_optInOn_noPendingReply_holdsServiceAndPersistentLease() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.ConnectedInBackground, decision.notificationState)
    }

    @Test
    fun testBackground_optInOn_pendingReply_holdsBoth_notificationPrefersWaiting() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = true,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.WaitingForReplies, decision.notificationState)
    }

    @Test
    fun testDeparting_holdsServiceBeforeBackgroundCompletes() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = true,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = true,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
    }

    @Test
    fun testBackground_noNetwork_showsWaitingForNetwork() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                hasActiveNetwork = false,
                isConnected = false,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertEquals(BackgroundNotificationState.WaitingForNetwork, decision.notificationState)
    }

    @Test
    fun testBackground_reconnecting_showsReconnecting() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                hasActiveNetwork = true,
                isConnected = false,
                isReconnecting = true,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertEquals(BackgroundNotificationState.Reconnecting, decision.notificationState)
    }

    @Test
    fun testAuthExpired_releasesServiceAndLease_showsNone() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = true,
                isEligibleForConnection = true,
                isAuthExpired = true,
                hasActiveNetwork = true,
                isConnected = false,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testDisconnected_whenAutoReconnectDisabled_releasesServiceAndLease_showsNone() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                isAuthExpired = false,
                isAutoReconnect = false,
                hasActiveNetwork = true,
                isConnected = false,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testDisconnected_whenAutoReconnectEnabled_holdsServiceAndShowsReconnecting() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                isAuthExpired = false,
                isAutoReconnect = true,
                hasActiveNetwork = true,
                isConnected = false,
                isReconnecting = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.Reconnecting, decision.notificationState)
    }
}
