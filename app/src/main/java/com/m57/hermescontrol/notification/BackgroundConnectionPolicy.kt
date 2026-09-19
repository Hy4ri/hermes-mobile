package com.m57.hermescontrol.notification

/**
 * Snapshot of runtime state required to determine background connection policy.
 */
data class BackgroundConnectionSnapshot(
    val appInForeground: Boolean,
    val isDeparting: Boolean = false,
    val keepConnectedOptIn: Boolean,
    val pendingReply: Boolean,
    val isEligibleForConnection: Boolean,
    val hasActiveNetwork: Boolean = true,
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
)

/**
 * User-visible state of the ongoing background notification.
 */
enum class BackgroundNotificationState {
    None,
    WaitingForNetwork,
    Reconnecting,
    WaitingForReplies,
    ConnectedInBackground,
}

/**
 * Evaluated decision for foreground service and connection retention.
 */
data class BackgroundConnectionDecision(
    val shouldHoldService: Boolean,
    val shouldHoldPersistentLease: Boolean,
    val notificationState: BackgroundNotificationState,
)

/**
 * Pure policy evaluation for background connection lifecycle.
 */
object BackgroundConnectionPolicy {
    fun evaluate(snapshot: BackgroundConnectionSnapshot): BackgroundConnectionDecision {
        if (!snapshot.isEligibleForConnection) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        // When the app is in foreground and not in the process of departing to background,
        // no background service or background lease is needed.
        if (snapshot.appInForeground && !snapshot.isDeparting) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        val hasDemand = snapshot.pendingReply || snapshot.keepConnectedOptIn
        if (!hasDemand) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        val notificationState =
            when {
                !snapshot.hasActiveNetwork -> BackgroundNotificationState.WaitingForNetwork
                snapshot.isReconnecting -> BackgroundNotificationState.Reconnecting
                snapshot.pendingReply -> BackgroundNotificationState.WaitingForReplies
                snapshot.isConnected -> BackgroundNotificationState.ConnectedInBackground
                else -> BackgroundNotificationState.Reconnecting
            }

        return BackgroundConnectionDecision(
            shouldHoldService = true,
            shouldHoldPersistentLease = snapshot.keepConnectedOptIn,
            notificationState = notificationState,
        )
    }
}
