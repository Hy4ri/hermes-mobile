package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.ws.ConnectionStatus

/**
 * Snapshot of runtime state required to determine background connection policy.
 */
data class BackgroundConnectionSnapshot(
    val appInForeground: Boolean,
    val isDeparting: Boolean = false,
    val keepConnectedOptIn: Boolean,
    val pendingReply: Boolean,
    val isEligibleForConnection: Boolean,
    val status: ConnectionStatus,
    val isAutoReconnect: Boolean = true,
    val hasActiveNetwork: Boolean = true,
) {
    companion object {
        fun ineligible(
            appInForeground: Boolean = false,
            isDeparting: Boolean = false,
        ): BackgroundConnectionSnapshot =
            BackgroundConnectionSnapshot(
                appInForeground = appInForeground,
                isDeparting = isDeparting,
                keepConnectedOptIn = false,
                pendingReply = false,
                isEligibleForConnection = false,
                status = ConnectionStatus.DISCONNECTED,
                isAutoReconnect = false,
                hasActiveNetwork = false,
            )
    }
}

/**
 * User-visible state of the ongoing background notification.
 */
enum class BackgroundNotificationState {
    None,
    WaitingForNetwork,
    Connecting,
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
        // Terminal failure / unauthenticated / ineligible state
        if (!snapshot.isEligibleForConnection || snapshot.status == ConnectionStatus.AUTH_EXPIRED) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        // When the app is in foreground and not departing, background service/lease are not needed
        if (snapshot.appInForeground && !snapshot.isDeparting) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        // Demand check: need either a pending reply or persistent opt-in
        val hasDemand = snapshot.pendingReply || snapshot.keepConnectedOptIn
        if (!hasDemand) {
            return BackgroundConnectionDecision(
                shouldHoldService = false,
                shouldHoldPersistentLease = false,
                notificationState = BackgroundNotificationState.None,
            )
        }

        // When Auto-Reconnect is disabled, HermesWsClient will not reconnect once disconnected or offline.
        // Even if a reply was pending, it can never arrive without reconnecting.
        if (!snapshot.isAutoReconnect) {
            when (snapshot.status) {
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.NO_NETWORK,
                -> {
                    return BackgroundConnectionDecision(
                        shouldHoldService = false,
                        shouldHoldPersistentLease = false,
                        notificationState = BackgroundNotificationState.None,
                    )
                }

                else -> { /* CONNECTED, CONNECTING, RECONNECTING can proceed */ }
            }
        }

        val notificationState =
            when {
                !snapshot.hasActiveNetwork || snapshot.status == ConnectionStatus.NO_NETWORK -> {
                    BackgroundNotificationState.WaitingForNetwork
                }

                snapshot.status == ConnectionStatus.RECONNECTING -> {
                    BackgroundNotificationState.Reconnecting
                }

                snapshot.pendingReply -> {
                    BackgroundNotificationState.WaitingForReplies
                }

                snapshot.status == ConnectionStatus.CONNECTED -> {
                    BackgroundNotificationState.ConnectedInBackground
                }

                snapshot.status == ConnectionStatus.CONNECTING -> {
                    BackgroundNotificationState.Connecting
                }

                else -> {
                    BackgroundNotificationState.Reconnecting
                }
            }

        return BackgroundConnectionDecision(
            shouldHoldService = true,
            shouldHoldPersistentLease = snapshot.keepConnectedOptIn,
            notificationState = notificationState,
        )
    }
}
