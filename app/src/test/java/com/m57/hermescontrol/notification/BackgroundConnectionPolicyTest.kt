package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.ws.ConnectionStatus
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
    }

    @Test
    fun testBackground_noNetwork_whenAutoReconnectEnabled_showsWaitingForNetwork() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.NO_NETWORK,
                isAutoReconnect = true,
                hasActiveNetwork = false,
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
                status = ConnectionStatus.RECONNECTING,
                isAutoReconnect = true,
                hasActiveNetwork = true,
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
                status = ConnectionStatus.AUTH_EXPIRED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testDisconnected_whenAutoReconnectDisabled_releasesServiceAndLease_evenWithPendingReply() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = true,
                isEligibleForConnection = true,
                status = ConnectionStatus.DISCONNECTED,
                isAutoReconnect = false,
                hasActiveNetwork = true,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testNoNetwork_whenAutoReconnectDisabled_releasesServiceAndLease() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.NO_NETWORK,
                isAutoReconnect = false,
                hasActiveNetwork = false,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertFalse(decision.shouldHoldService)
        assertFalse(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.None, decision.notificationState)
    }

    @Test
    fun testConnecting_whenAutoReconnectDisabled_allowsServiceAndLease() {
        val state =
            BackgroundConnectionSnapshot(
                appInForeground = false,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.CONNECTING,
                isAutoReconnect = false,
                hasActiveNetwork = true,
            )
        val decision = BackgroundConnectionPolicy.evaluate(state)
        assertTrue(decision.shouldHoldService)
        assertTrue(decision.shouldHoldPersistentLease)
        assertEquals(BackgroundNotificationState.ConnectedInBackground, decision.notificationState)
    }
}
