package com.m57.hermescontrol.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AppLockStore
import com.m57.hermescontrol.data.session.ActiveSessionHolder

/** Builds and posts the notifications for gateway requests (approval, clarify, credentials). */
internal object RequestNotifications {
    /** One id; the per-request key is the notification tag. */
    private const val REQUEST_NOTIFICATION_ID = 20_000
    private const val OUTCOME_TIMEOUT_MS = 6_000L

    fun post(
        context: Context,
        notice: RequestNotice,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val body =
            when (notice.kind) {
                RequestNoticeKind.APPROVAL -> {
                    notice.preview?.take(100) ?: context.getString(R.string.notif_input_needed)
                }

                RequestNoticeKind.CLARIFY -> {
                    context.getString(R.string.notif_clarification_needed)
                }

                RequestNoticeKind.CREDENTIAL -> {
                    context.getString(R.string.notif_input_needed)
                }
            }
        val builder = base(context, notice.key, notice.sessionId, body)
        if (canOfferActions(context, notice)) {
            if (notice.canApproveOnce) {
                builder.addAction(
                    action(context, notice.key, ApprovalActionHandler.CHOICE_ONCE, R.string.notif_action_approve_once),
                )
            }
            if (notice.canDeny) {
                builder.addAction(
                    action(context, notice.key, ApprovalActionHandler.CHOICE_DENY, R.string.notif_action_deny),
                )
            }
        }
        manager.notify(notice.key, REQUEST_NOTIFICATION_ID, builder.build())
    }

    fun cancel(
        context: Context,
        key: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(key, REQUEST_NOTIFICATION_ID)
    }

    /** Replace the alert with the truthful result of the action. */
    fun postOutcome(
        context: Context,
        key: String,
        notice: RequestNotice?,
        choice: String,
        outcome: ApprovalActionOutcome,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (outcome == ApprovalActionOutcome.STALE) {
            manager.cancel(key, REQUEST_NOTIFICATION_ID)
            return
        }
        val text =
            when (outcome) {
                ApprovalActionOutcome.SENT,
                ApprovalActionOutcome.RESOLVED,
                -> {
                    context.getString(
                        if (choice == ApprovalActionHandler.CHOICE_ONCE) {
                            R.string.notif_approval_sent_once
                        } else {
                            R.string.notif_approval_sent_deny
                        },
                    )
                }

                else -> {
                    context.getString(R.string.notif_approval_open_app)
                }
            }
        val builder = base(context, key, notice?.sessionId, text)
        if (outcome == ApprovalActionOutcome.SENT || outcome == ApprovalActionOutcome.RESOLVED) {
            builder.setTimeoutAfter(OUTCOME_TIMEOUT_MS)
        }
        manager.notify(key, REQUEST_NOTIFICATION_ID, builder.build())
    }

    private fun base(
        context: Context,
        key: String,
        sessionId: String?,
        body: String,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, key, sessionId))

    /**
     * Actions need a device-unlock gate (API 31+) and no app lock. Below 31 the
     * lockscreen cannot be proven, so the user must open the app.
     */
    private fun canOfferActions(
        context: Context,
        notice: RequestNotice,
    ): Boolean =
        notice.answerable &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !AppLockStore.blocksNotificationReplies(context)

    private fun action(
        context: Context,
        key: String,
        choice: String,
        label: Int,
    ): NotificationCompat.Action {
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = "${context.packageName}.ACTION_APPROVAL_$choice"
                component = ComponentName(context, ApprovalActionReceiver::class.java)
                setPackage(context.packageName)
                putExtra(ApprovalActionReceiver.EXTRA_REQUEST_KEY, key)
                putExtra(ApprovalActionReceiver.EXTRA_CHOICE, choice)
            }
        val pending =
            PendingIntent.getBroadcast(
                context,
                "$key|$choice".hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action
            .Builder(R.drawable.ic_notification, context.getString(label), pending)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun contentIntent(
        context: Context,
        key: String,
        runtimeSessionId: String?,
    ): PendingIntent {
        // Only the live session has a stored id the app can open; otherwise open the app.
        val stored =
            runtimeSessionId
                ?.takeIf { it == ActiveSessionHolder.activeSessionId.value }
                ?.let { ActiveSessionHolder.resolveStoredSessionId(it) }
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION
                component = ComponentName(context, MainActivity::class.java)
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!stored.isNullOrBlank()) {
                    putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, stored)
                }
            }
        return PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
