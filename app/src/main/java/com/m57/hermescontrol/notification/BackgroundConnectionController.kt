package com.m57.hermescontrol.notification

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.ws.HermesWsClient

/**
 * Coordinates background connection leases and foreground service lifecycle
 * based on [BackgroundConnectionPolicy].
 */
class BackgroundConnectionController(
    private val snapshotProvider: (isDeparting: Boolean) -> BackgroundConnectionSnapshot = { isDeparting ->
        defaultSnapshot(isDeparting)
    },
    private val acquireLease: () -> Unit = { HermesWsClient.acquireBackgroundConnectionLease() },
    private val releaseLease: () -> Unit = { HermesWsClient.releaseBackgroundConnectionLease() },
    private val requestServiceStart: (() -> Unit) -> Unit = { startAction ->
        ChatNotificationService.lifecycle.start(startAction)
    },
    private val requestServiceStop: () -> Unit = { ChatNotificationService.lifecycle.stop() },
    private val requestServiceComplete: (Long) -> Unit = { generation ->
        ChatNotificationService.lifecycle.complete(generation)
    },
    private val onNotificationStateChanged: (BackgroundNotificationState) -> Unit = { state ->
        ChatNotificationService.updateForegroundNotification(state)
    },
) {
    companion object {
        private const val TAG = "BgConnectionController"

        val default: BackgroundConnectionController by lazy { BackgroundConnectionController() }

        fun defaultSnapshot(isDeparting: Boolean = false): BackgroundConnectionSnapshot {
            if (AuthManager.initializationState.value != AuthManager.InitializationState.Ready) {
                return BackgroundConnectionSnapshot.ineligible(
                    appInForeground = ChatNotificationService.isAppInForeground(),
                    isDeparting = isDeparting,
                )
            }

            val status = HermesWsClient.connectionStatus.value
            val isEligible = AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()

            return BackgroundConnectionSnapshot(
                appInForeground = ChatNotificationService.isAppInForeground(),
                isDeparting = isDeparting,
                keepConnectedOptIn = AuthManager.isKeepConnectedInBackground(),
                pendingReply = HermesWsClient.pendingReply,
                isEligibleForConnection = isEligible,
                status = status,
                isAutoReconnect = AuthManager.isAutoReconnect(),
                hasActiveNetwork = NetworkMonitor.isConnected.value,
            )
        }
    }

    private var currentNotificationState: BackgroundNotificationState = BackgroundNotificationState.None

    fun onAppPause(startServiceAction: () -> Unit) {
        val snapshot = snapshotProvider(true)
        val decision = BackgroundConnectionPolicy.evaluate(snapshot)

        if (!decision.shouldHoldService) {
            return
        }

        if (decision.shouldHoldPersistentLease) {
            acquireLease()
        }

        try {
            requestServiceStart(startServiceAction)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start background service, rolling back lease", e)
            if (decision.shouldHoldPersistentLease) {
                releaseLease()
            }
        }
    }

    fun onAppResume() {
        releaseLease()
        requestServiceStop()
    }

    fun onReplyCompleted(generation: Long) {
        val snapshot = snapshotProvider(false).copy(pendingReply = false)
        val decision = BackgroundConnectionPolicy.evaluate(snapshot)

        if (decision.shouldHoldService) {
            if (decision.notificationState != currentNotificationState) {
                currentNotificationState = decision.notificationState
                onNotificationStateChanged(decision.notificationState)
            }
        } else {
            requestServiceComplete(generation)
        }
    }

    fun reconcileState() {
        val snapshot = snapshotProvider(false)
        val decision = BackgroundConnectionPolicy.evaluate(snapshot)

        if (!decision.shouldHoldService && !snapshot.appInForeground) {
            releaseLease()
            requestServiceStop()
        } else if (decision.shouldHoldService) {
            if (decision.notificationState != currentNotificationState) {
                currentNotificationState = decision.notificationState
                onNotificationStateChanged(decision.notificationState)
            }
        }
    }
}
