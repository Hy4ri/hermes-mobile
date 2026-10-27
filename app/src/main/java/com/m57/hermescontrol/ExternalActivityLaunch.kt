package com.m57.hermescontrol

import android.content.Context
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper

/**
 * Launches an external activity under the app's standard guard: the WS connection lease and the
 * background reply service, released when [ExternalActivityLifecycleGuard.externalActivityReturned] runs.
 */
fun launchExternalActivityWithLease(
    context: Context,
    launch: () -> Unit,
) {
    ExternalActivityLifecycleGuard.launchExternalActivity(
        acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
        releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
        prepareForBackground = { NotificationHelper.start(context) },
        cleanupAfterLaunchFailure = { NotificationHelper.stop(context) },
        launch = launch,
    )
}
