package com.m57.hermescontrol.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.m57.hermescontrol.applock.AppLockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Approve once / Deny notification actions. Not exported; reached
 * only through immutable explicit PendingIntents that name one request.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_REQUEST_KEY = "extra_request_key"
        const val EXTRA_CHOICE = "extra_choice"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val key = intent.getStringExtra(EXTRA_REQUEST_KEY) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val notice = RequestNoticeCoordinator.notices.find(key)?.first
                val outcome =
                    ApprovalActionHandler.handle(
                        key = key,
                        choice = choice,
                        currentScopeId = correlationScopeId(),
                        appLocked = AppLockStore.blocksNotificationReplies(app),
                        transport = HermesApprovalTransport,
                    )
                RequestNotifications.postOutcome(app, key, notice, choice, outcome)
            } catch (e: Exception) {
                android.util.Log.e("ApprovalAction", "Failed to process approval action", e)
            } finally {
                pending.finish()
            }
        }
    }
}
