package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded and posts a notification when a new assistant reply
 * completes (MessageComplete event) while the app is not in the foreground.
 *
 * FGS type: `remoteMessaging` — indefinite listener that maintains a
 * long-lived connection to a remote server for receiving messages. This is
 * the correct type per Android 14+ policy (dataSync has a time budget and
 * is intended for finite sync operations).
 *
 * Channel importance: IMPORTANCE_MIN — the ongoing notification is a
 * persistent indicator, not an alert. PRIORITY_MIN matches.
 * The separate `hermes_chat` channel uses IMPORTANCE_HIGH for actual
 * message notifications, which is correct.
 *
 * Lifecycle:
 * - Started by [NotificationHelper.start] when the app goes to the
 *   background while a reply is still pending (the user sent a message or
 *   replied from a notification and the agent has not finished yet).
 *   [NotificationHelper.start] is a no-op when nothing is pending, so the
 *   service — and its mandatory persistent notification — only exists
 *   while the user is actually waiting for a reply (issue #794).
 * - Stopped by [NotificationHelper.stop] when the app returns to the
 *   foreground (called from MainActivity.onStart), or by the
 *   service itself once the pending reply completes in the background
 *   (the reply notification replaces the persistent "waiting" one).
 *
 * The service collects [WsEvent]s from [HermesWsClient] — the same stream
 * the ChatViewModel collects — and watches for [WsEvent.MessageComplete]
 * events that indicate the agent has finished replying.
 */
class ChatNotificationService : Service() {
    companion object {
        internal const val SERVICE_CHANNEL_ID = "hermes_service"
        internal const val CHAT_CHANNEL_ID = "hermes_chat"
        internal const val NOTIFICATION_ID = 1
        internal const val PENDING_NOTIFICATION_ID = 2

        private val isAppInForeground = AtomicBoolean(false)

        fun setAppForeground(foreground: Boolean) {
            isAppInForeground.set(foreground)
        }

        fun isAppInForeground(): Boolean = isAppInForeground.get()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventCollector: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification(getString(R.string.notif_waiting_replies)))
        startEventCollection()
    }

    private fun startEventCollection() {
        eventCollector =
            serviceScope.launch {
                HermesWsClient.events.collect { event ->
                    if (!isAppInForeground.get()) {
                        launch {
                            delay(500)
                            if (!isAppInForeground.get()) {
                                when (event) {
                                    is WsEvent.MessageComplete -> {
                                        val preview =
                                            event.text
                                                .take(100)
                                                .replace("\n", " ")
                                                .ifBlank { getString(R.string.notif_new_message) }
                                        showReplyNotification(
                                            preview,
                                            event.storedSessionId
                                                ?: ActiveSessionHolder.resolveStoredSessionId(event.sessionId),
                                        )
                                        // The wait is over — retire the foreground
                                        // service. The reply notification above
                                        // replaces the persistent "waiting" one,
                                        // and the pendingReply flag is cleared by
                                        // HermesWsClient's own collector, so the
                                        // service is not restarted on the next
                                        // ON_STOP (issue #794).
                                        stopForeground(STOP_FOREGROUND_REMOVE)
                                        stopSelf()
                                    }

                                    is WsEvent.ClarifyRequest -> {
                                        showReplyNotification(getString(R.string.notif_clarification_needed), null)
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun showReplyNotification(
        text: String,
        sessionId: String?,
    ) {
        val builder =
            NotificationCompat
                .Builder(this, CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent(sessionId))

        if (!sessionId.isNullOrBlank()) {
            val replyLabel = getString(R.string.notif_reply_placeholder)
            val remoteInput =
                RemoteInput
                    .Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
                    .setLabel(replyLabel)
                    .build()

            val replyIntent =
                Intent(this, NotificationReplyReceiver::class.java).apply {
                    action = "$packageName.ACTION_NOTIFICATION_REPLY"
                    setPackage(packageName)
                    putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
                }

            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    sessionId.hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT,
                )

            val action =
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_notification,
                        getString(R.string.action_reply),
                        replyPendingIntent,
                    ).addRemoteInput(remoteInput)
                    .build()

            builder.addAction(action)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PENDING_NOTIFICATION_ID, builder.build())
    }

    private fun buildContentIntent(sessionId: String?): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        if (!sessionId.isNullOrBlank()) {
            intent.putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            this,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT,
        )
    }

    private fun buildForegroundNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel =
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    getString(R.string.notif_channel_service_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.notif_channel_service_desc)
                }

            val chatChannel =
                NotificationChannel(
                    CHAT_CHANNEL_ID,
                    getString(R.string.notif_channel_chat_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.notif_channel_chat_desc)
                }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(chatChannel)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        eventCollector?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

/**
 * Helper to start/stop the notification service from the UI layer.
 */
object NotificationHelper {
    fun start(context: Context) {
        if (AuthManager.getToken().isNullOrBlank()) return
        // Only run the foreground service while a reply is actually pending
        // (issue #794) — otherwise the mandatory persistent "Waiting for
        // Hermes replies" notification appears on every background even
        // when nothing is in flight.
        if (!HermesWsClient.pendingReply) return
        val intent = Intent(context, ChatNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ChatNotificationService::class.java))
    }

    fun setAppForeground(
        context: Context,
        foreground: Boolean,
    ) {
        ChatNotificationService.setAppForeground(foreground)
        HermesWsClient.setAppForeground(foreground)
    }

    fun isAppInForeground(): Boolean = ChatNotificationService.isAppInForeground()
}
