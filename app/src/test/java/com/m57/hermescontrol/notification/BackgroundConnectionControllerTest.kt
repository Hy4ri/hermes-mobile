package com.m57.hermescontrol.notification

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackgroundConnectionControllerTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testOnAppPause_whenOptIn_acquiresLeaseAndRequestsStart() {
        var leaseAcquired = false
        var serviceStartRequested = false
        var startActionInvoked = false

        val snapshot =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { isDeparting -> snapshot.copy(isDeparting = isDeparting) },
                acquireLease = { leaseAcquired = true },
                releaseLease = { leaseAcquired = false },
                requestServiceStart = { action ->
                    serviceStartRequested = true
                    action()
                },
            )

        controller.onAppPause {
            startActionInvoked = true
        }

        assertTrue(leaseAcquired)
        assertTrue(serviceStartRequested)
        assertTrue(startActionInvoked)
    }

    @Test
    fun testOnAppPause_whenStartThrowsException_rollsBackLeaseAndDoesNotRethrow() {
        var leaseAcquired = false
        var leaseReleased = false

        val snapshot =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = false,
                keepConnectedOptIn = true,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { isDeparting -> snapshot.copy(isDeparting = isDeparting) },
                acquireLease = { leaseAcquired = true },
                releaseLease = { leaseReleased = true },
                requestServiceStart = { _ ->
                    throw IllegalStateException("Background start not allowed")
                },
            )

        // Must not rethrow into Activity.onPause, but must roll back acquired lease
        controller.onAppPause {}

        assertTrue(leaseAcquired)
        assertTrue(leaseReleased)
    }

    @Test
    fun testOnAppPause_whenOptInOffAndNoPendingReply_doesNothing() {
        var leaseAcquired = false
        var serviceStartRequested = false

        val snapshot =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = false,
                keepConnectedOptIn = false,
                pendingReply = false,
                isEligibleForConnection = true,
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { isDeparting -> snapshot.copy(isDeparting = isDeparting) },
                acquireLease = { leaseAcquired = true },
                releaseLease = { leaseAcquired = false },
                requestServiceStart = { serviceStartRequested = true },
            )

        controller.onAppPause {}

        assertFalse(leaseAcquired)
        assertFalse(serviceStartRequested)
    }

    @Test
    fun testOnAppPause_whenPendingReplyOnly_requestsServiceWithoutPersistentLease() {
        var leaseAcquired = false
        var serviceStartRequested = false

        val snapshot =
            BackgroundConnectionSnapshot(
                appInForeground = true,
                isDeparting = false,
                keepConnectedOptIn = false,
                pendingReply = true,
                isEligibleForConnection = true,
                status = ConnectionStatus.CONNECTED,
                isAutoReconnect = true,
                hasActiveNetwork = true,
            )

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { isDeparting -> snapshot.copy(isDeparting = isDeparting) },
                acquireLease = { leaseAcquired = true },
                releaseLease = { leaseAcquired = false },
                requestServiceStart = { serviceStartRequested = true },
            )

        controller.onAppPause {}

        assertFalse(leaseAcquired)
        assertTrue(serviceStartRequested)
    }

    @Test
    fun testOnAppResume_releasesLeaseAndStopsService() {
        var leaseReleased = false
        var serviceStopped = false

        val controller =
            BackgroundConnectionController(
                releaseLease = { leaseReleased = true },
                requestServiceStop = { serviceStopped = true },
            )

        controller.onAppResume()

        assertTrue(leaseReleased)
        assertTrue(serviceStopped)
    }

    @Test
    fun testOnReplyCompleted_whenOptInOn_keepsServiceAndUpdatesNotification() {
        var serviceCompleted = false
        var notifiedState: BackgroundNotificationState? = null

        val snapshot =
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

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { snapshot },
                requestServiceComplete = { serviceCompleted = true },
                onNotificationStateChanged = { state -> notifiedState = state },
            )

        controller.onReplyCompleted(42L)

        assertFalse(serviceCompleted)
        assertEquals(BackgroundNotificationState.ConnectedInBackground, notifiedState)
    }

    @Test
    fun testOnReplyCompleted_whenOptInOff_completesService() {
        var completedGeneration: Long? = null

        val snapshot =
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

        val controller =
            BackgroundConnectionController(
                snapshotProvider = { snapshot },
                requestServiceComplete = { gen -> completedGeneration = gen },
            )

        controller.onReplyCompleted(42L)

        assertEquals(42L, completedGeneration)
    }

    @Test
    fun testDefaultSnapshot_whenNotReady_returnsIneligibleSnapshotWithoutReadingServerStore() {
        mockkObject(AuthManager)
        mockkObject(ChatNotificationService)
        mockkObject(HermesWsClient)
        mockkObject(NetworkMonitor)
        try {
            every { AuthManager.initializationState } returns MutableStateFlow(AuthManager.InitializationState.Loading)
            every { ChatNotificationService.isAppInForeground() } returns true

            val snapshot = BackgroundConnectionController.defaultSnapshot()

            assertFalse(snapshot.isEligibleForConnection)
            assertFalse(snapshot.keepConnectedOptIn)
            assertFalse(snapshot.pendingReply)
            assertEquals(ConnectionStatus.DISCONNECTED, snapshot.status)

            // Must NOT call any serverStore getters before Ready
            verify(exactly = 0) { AuthManager.isGatedMode() }
            verify(exactly = 0) { AuthManager.getToken() }
            verify(exactly = 0) { AuthManager.isKeepConnectedInBackground() }
            verify(exactly = 0) { AuthManager.isAutoReconnect() }

            val decision = BackgroundConnectionPolicy.evaluate(snapshot)
            assertFalse(decision.shouldHoldService)
            assertFalse(decision.shouldHoldPersistentLease)
            assertEquals(BackgroundNotificationState.None, decision.notificationState)
        } finally {
            unmockkObject(AuthManager)
            unmockkObject(ChatNotificationService)
            unmockkObject(HermesWsClient)
            unmockkObject(NetworkMonitor)
        }
    }

    @Test
    fun testDefaultSnapshot_whenReadyGatedModeWithNullToken_isEligibleForConnection() {
        mockkObject(AuthManager)
        mockkObject(ChatNotificationService)
        mockkObject(HermesWsClient)
        mockkObject(NetworkMonitor)
        try {
            every { AuthManager.initializationState } returns MutableStateFlow(AuthManager.InitializationState.Ready)
            every { AuthManager.isGatedMode() } returns true
            every { AuthManager.getToken() } returns null
            every { AuthManager.isKeepConnectedInBackground() } returns true
            every { AuthManager.isAutoReconnect() } returns true
            every { ChatNotificationService.isAppInForeground() } returns false
            every { HermesWsClient.pendingReply } returns false
            every { HermesWsClient.connectionStatus } returns MutableStateFlow(ConnectionStatus.CONNECTED)
            every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

            val snapshot = BackgroundConnectionController.defaultSnapshot()
            assertTrue(snapshot.isEligibleForConnection)
            assertEquals(ConnectionStatus.CONNECTED, snapshot.status)

            val decision = BackgroundConnectionPolicy.evaluate(snapshot)
            assertTrue(decision.shouldHoldService)
            assertTrue(decision.shouldHoldPersistentLease)
            assertEquals(BackgroundNotificationState.ConnectedInBackground, decision.notificationState)
        } finally {
            unmockkObject(AuthManager)
            unmockkObject(ChatNotificationService)
            unmockkObject(HermesWsClient)
            unmockkObject(NetworkMonitor)
        }
    }

    @Test
    fun testDefaultSnapshot_whenReadyNotGatedModeAndNullToken_isNotEligible() {
        mockkObject(AuthManager)
        mockkObject(ChatNotificationService)
        mockkObject(HermesWsClient)
        mockkObject(NetworkMonitor)
        try {
            every { AuthManager.initializationState } returns MutableStateFlow(AuthManager.InitializationState.Ready)
            every { AuthManager.isGatedMode() } returns false
            every { AuthManager.getToken() } returns null
            every { AuthManager.isKeepConnectedInBackground() } returns true
            every { AuthManager.isAutoReconnect() } returns true
            every { ChatNotificationService.isAppInForeground() } returns false
            every { HermesWsClient.pendingReply } returns false
            every { HermesWsClient.connectionStatus } returns MutableStateFlow(ConnectionStatus.DISCONNECTED)
            every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

            val snapshot = BackgroundConnectionController.defaultSnapshot()
            assertFalse(snapshot.isEligibleForConnection)

            val decision = BackgroundConnectionPolicy.evaluate(snapshot)
            assertFalse(decision.shouldHoldService)
            assertFalse(decision.shouldHoldPersistentLease)
            assertEquals(BackgroundNotificationState.None, decision.notificationState)
        } finally {
            unmockkObject(AuthManager)
            unmockkObject(ChatNotificationService)
            unmockkObject(HermesWsClient)
            unmockkObject(NetworkMonitor)
        }
    }

    @Test
    fun testDefaultSnapshot_whenAuthExpired_policyReleasesService() {
        mockkObject(AuthManager)
        mockkObject(ChatNotificationService)
        mockkObject(HermesWsClient)
        mockkObject(NetworkMonitor)
        try {
            every { AuthManager.initializationState } returns MutableStateFlow(AuthManager.InitializationState.Ready)
            every { AuthManager.isGatedMode() } returns false
            every { AuthManager.getToken() } returns "some-token"
            every { AuthManager.isKeepConnectedInBackground() } returns true
            every { AuthManager.isAutoReconnect() } returns true
            every { ChatNotificationService.isAppInForeground() } returns false
            every { HermesWsClient.pendingReply } returns false
            every { HermesWsClient.connectionStatus } returns MutableStateFlow(ConnectionStatus.AUTH_EXPIRED)
            every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

            val snapshot = BackgroundConnectionController.defaultSnapshot()
            assertTrue(snapshot.isEligibleForConnection)
            assertEquals(ConnectionStatus.AUTH_EXPIRED, snapshot.status)

            val decision = BackgroundConnectionPolicy.evaluate(snapshot)
            assertFalse(decision.shouldHoldService)
            assertFalse(decision.shouldHoldPersistentLease)
            assertEquals(BackgroundNotificationState.None, decision.notificationState)
        } finally {
            unmockkObject(AuthManager)
            unmockkObject(ChatNotificationService)
            unmockkObject(HermesWsClient)
            unmockkObject(NetworkMonitor)
        }
    }

    @Test
    fun testDefaultSnapshot_whenAutoReconnectDisabledAndDisconnected_policyReleasesService() {
        mockkObject(AuthManager)
        mockkObject(ChatNotificationService)
        mockkObject(HermesWsClient)
        mockkObject(NetworkMonitor)
        try {
            every { AuthManager.initializationState } returns MutableStateFlow(AuthManager.InitializationState.Ready)
            every { AuthManager.isGatedMode() } returns false
            every { AuthManager.getToken() } returns "some-token"
            every { AuthManager.isKeepConnectedInBackground() } returns true
            every { AuthManager.isAutoReconnect() } returns false
            every { ChatNotificationService.isAppInForeground() } returns false
            every { HermesWsClient.pendingReply } returns false
            every { HermesWsClient.connectionStatus } returns MutableStateFlow(ConnectionStatus.DISCONNECTED)
            every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

            val snapshot = BackgroundConnectionController.defaultSnapshot()
            assertTrue(snapshot.isEligibleForConnection)
            assertEquals(ConnectionStatus.DISCONNECTED, snapshot.status)
            assertFalse(snapshot.isAutoReconnect)

            val decision = BackgroundConnectionPolicy.evaluate(snapshot)
            assertFalse(decision.shouldHoldService)
            assertFalse(decision.shouldHoldPersistentLease)
            assertEquals(BackgroundNotificationState.None, decision.notificationState)
        } finally {
            unmockkObject(AuthManager)
            unmockkObject(ChatNotificationService)
            unmockkObject(HermesWsClient)
            unmockkObject(NetworkMonitor)
        }
    }
}
