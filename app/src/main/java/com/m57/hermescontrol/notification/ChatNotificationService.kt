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
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded and posts a notification when a new assistant reply
 * completes (MessageComplete event) while the app is not in the foreground.
 *
 * Lifecycle:
 * - Started by [NotificationHelper.start] when the user sends a message and
 *   the app is about to go to the background (called from ChatScreen's
 *   onStop / onPause).
 * - Stopped by [NotificationHelper.stop] when the app returns to the
 *   foreground (called from ChatScreen's onStart / onResume).
 *
 * The service collects [WsEvent]s from [HermesWsClient] — the same stream
 * the ChatViewModel collects — and watches for [WsEvent.MessageComplete]
 * events that indicate the agent has finished replying.
 */
class ChatNotificationService : Service() {
    companion object {
        private const val CHANNEL_ID = "hermes_chat"
        private const val NOTIFICATION_ID = 1
        private const val PENDING_NOTIFICATION_ID = 2

        private var isAppInForeground = false

        fun setAppForeground(foreground: Boolean) {
            isAppInForeground = foreground
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventCollector: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Hermes connected"))
        startEventCollection()
    }

    private fun startEventCollection() {
        eventCollector =
            serviceScope.launch {
                HermesWsClient.events.collect { event ->
                    if (!isAppInForeground) {
                        when (event) {
                            is WsEvent.MessageComplete -> {
                                val preview =
                                    event.text
                                        .take(100)
                                        .replace("\n", " ")
                                        .ifBlank { "New message" }
                                showReplyNotification(preview)
                            }
                            is WsEvent.ClarifyRequest -> {
                                showReplyNotification("Hermes needs a clarification")
                            }
                            else -> {}
                        }
                    }
                }
            }
    }

    private fun showReplyNotification(text: String) {
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Hermes")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent())
                .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PENDING_NOTIFICATION_ID, notification)
    }

    private fun buildContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildForegroundNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hermes")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Hermes Chat",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Notifications for new Hermes replies"
                }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

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
    }
}
