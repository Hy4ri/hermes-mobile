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
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.replyFailureFromPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal data class MessageCompleteNotificationPlan(
    val text: String,
    val sessionId: String?,
    val isReplyMessage: Boolean,
    val completionId: String?,
    val correlationText: String?,
    val allowInlineReply: Boolean,
)

internal fun messageCompleteNotificationPlan(
    event: WsEvent.MessageComplete,
    targetSessionId: String?,
    newMessageText: String,
    failureText: String,
): MessageCompleteNotificationPlan {
    if (replyFailureFromPayload(event.rawPayload, fallback = "") != null) {
        return MessageCompleteNotificationPlan(
            text = failureText,
            sessionId = targetSessionId,
            isReplyMessage = false,
            completionId = null,
            correlationText = null,
            allowInlineReply = false,
        )
    }

    return MessageCompleteNotificationPlan(
        text =
            event.text
                .take(100)
                .replace("\n", " ")
                .ifBlank { newMessageText },
        sessionId = targetSessionId,
        isReplyMessage = true,
        completionId = event.completionId,
        correlationText = event.text,
        allowInlineReply = true,
    )
}

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
 * - Started by [NotificationHelper.start] when the app goes to the background
 *   either while a reply is still pending (issue #794) or when persistent background
 *   connection ([com.m57.hermescontrol.data.local.AuthManager.isKeepConnectedInBackground])
 *   is enabled.
 * - In persistent keep-connected mode, the service stays alive across idle states
 *   and updates its ongoing notification truthfully ([BackgroundNotificationState]).
 * - In replies-only mode (default), the service retires itself once the pending reply
 *   completes in the background.
 * - Stopped by [NotificationHelper.stop] when the app returns to the foreground, or
 *   retired automatically upon auth expiry or terminal disconnection.
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
        internal val lifecycle = ForegroundServiceLifecycle()

        /**
         * Turn-row correlation used when a reply completes in the background.
         * Injectable so the resolution path can be exercised without REST.
         */
        internal var turnRowResolver: TurnRowResolver = defaultTurnRowResolver

        @Volatile
        internal var activeServiceInstance: ChatNotificationService? = null

        fun setAppForeground(foreground: Boolean) {
            isAppInForeground.set(foreground)
        }

        fun isAppInForeground(): Boolean = isAppInForeground.get()

        fun updateForegroundNotification(state: BackgroundNotificationState) {
            activeServiceInstance?.updateNotification(state)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventCollector: Job? = null
    private var statusCollector: Job? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannels()
        startEventCollection()
        startStatusCollection()
    }

    private fun startEventCollection() {
        eventCollector =
            serviceScope.launch {
                HermesWsClient.events.collect { event ->
                    if (!isAppInForeground.get()) {
                        val generation = lifecycle.generation
                        launch {
                            delay(500)
                            if (!isAppInForeground.get()) {
                                when (event) {
                                    is WsEvent.MessageComplete -> {
                                        val targetSessionId =
                                            event.storedSessionId
                                                ?: ActiveSessionHolder.resolveStoredSessionId(event.sessionId)
                                        val plan =
                                            messageCompleteNotificationPlan(
                                                event = event,
                                                targetSessionId = targetSessionId,
                                                newMessageText = getString(R.string.notif_new_message),
                                                failureText = getString(R.string.chat_reply_failed_title),
                                            )
                                        showReplyNotification(
                                            text = plan.text,
                                            sessionId = plan.sessionId,
                                            isReplyMessage = plan.isReplyMessage,
                                            completionId = plan.completionId,
                                            allowInlineReply = plan.allowInlineReply,
                                            // The durable REST row for this turn, when the
                                            // boundary armed before the prompt was submitted
                                            // still lets us name it unambiguously. Null is a
                                            // normal, safe outcome: the notification is then
                                            // never auto-dismissed from REST hydration, which
                                            // is strictly better than dismissing the wrong
                                            // duplicate reply.
                                            serverMessageId =
                                                coalesceTurnRow(targetSessionId, event.text),
                                            profileName = event.profileName,
                                        )
                                        // The wait is over — retire the foreground
                                        // service. The reply notification above
                                        // replaces the persistent "waiting" one,
                                        // and the pendingReply flag is cleared by
                                        // HermesWsClient's own collector, so the
                                        // service is not restarted on the next
                                        // ON_STOP (issue #794).
                                        // A delayed completion must not retire a newer turn/start.
                                        BackgroundConnectionController.default.onReplyCompleted(generation)
                                    }

                                    is WsEvent.ClarifyRequest -> {
                                        showReplyNotification(getString(R.string.notif_clarification_needed), null)
                                    }

                                    is WsEvent.SudoRequest -> {
                                        showReplyNotification(getString(R.string.notif_input_needed), null)
                                    }

                                    is WsEvent.SecretRequest -> {
                                        val body =
                                            event.prompt?.takeIf { it.isNotBlank() }
                                                ?: event.envVar?.takeIf { it.isNotBlank() }
                                                ?: getString(R.string.notif_input_needed)
                                        showReplyNotification(body, null)
                                    }

                                    is WsEvent.ApprovalRequest -> {
                                        val preview =
                                            event.description?.takeIf { it.isNotBlank() }
                                                ?: event.command?.takeIf { it.isNotBlank() }
                                                ?: getString(R.string.notif_input_needed)
                                        showReplyNotification(preview.take(100), null)
                                    }

                                    is WsEvent.VaultUnlockRequest,
                                    is WsEvent.VaultSaveLoginRequest,
                                    is WsEvent.VaultCodeRequest,
                                    -> {
                                        showReplyNotification(getString(R.string.notif_input_needed), null)
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
    }

    /**
     * Names the exact REST row this completion produced, using the turn
     * boundary armed before the prompt was submitted. Any failure to prove that
     * identity returns null — the notification is still posted, it just will not
     * be auto-dismissed from REST hydration.
     */
    private suspend fun coalesceTurnRow(
        sessionId: String?,
        completionText: String,
    ): Int? {
        if (sessionId.isNullOrBlank()) return null
        return try {
            correlateCompletedTurnRow(
                scopeId = correlationScopeId(),
                sessionId = sessionId,
                completionText = completionText,
                resolver = turnRowResolver,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun showReplyNotification(
        text: String,
        sessionId: String?,
        isReplyMessage: Boolean = false,
        completionId: String? = null,
        serverMessageId: Int? = null,
        allowInlineReply: Boolean = true,
        profileName: String? = null,
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
                .setContentIntent(buildContentIntent(sessionId, profileName))

        var replyGeneration: Long? = null
        if (isReplyMessage && !sessionId.isNullOrBlank() && !completionId.isNullOrBlank()) {
            val scopeId = correlationScopeId()
            val generation =
                ReplyNotificationTracker.registerPendingReply(
                    scopeId = scopeId,
                    sessionId = sessionId,
                    completionId = completionId,
                    textSnippet = text,
                    serverMessageId = serverMessageId,
                )
            replyGeneration = generation
            builder.addExtras(
                android.os.Bundle().apply {
                    putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, ReplyNotificationTracker.KIND_REPLY)
                    putString(ReplyNotificationTracker.EXTRA_SCOPE_ID, scopeId)
                    putString(ReplyNotificationTracker.EXTRA_SESSION_ID, sessionId)
                    putString(ReplyNotificationTracker.EXTRA_COMPLETION_ID, completionId)
                    putString(ReplyNotificationTracker.EXTRA_TEXT_SNIPPET, text)
                    putLong(ReplyNotificationTracker.EXTRA_GENERATION, generation)
                    serverMessageId?.let {
                        putInt(ReplyNotificationTracker.EXTRA_SERVER_MESSAGE_ID, it)
                    }
                    profileName?.let {
                        putString(NotificationReplyReceiver.EXTRA_PROFILE_NAME, it)
                    }
                },
            )
        } else {
            builder.addExtras(
                android.os.Bundle().apply {
                    putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, ReplyNotificationTracker.KIND_ACTION)
                    profileName?.let {
                        putString(NotificationReplyReceiver.EXTRA_PROFILE_NAME, it)
                    }
                },
            )
        }

        if (allowInlineReply && !sessionId.isNullOrBlank()) {
            val replyLabel = getString(R.string.notif_reply_placeholder)
            val remoteInput =
                RemoteInput
                    .Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
                    .setLabel(replyLabel)
                    .build()

            val replyIntent =
                Intent(this, NotificationReplyReceiver::class.java).apply {
                    action = "$packageName.ACTION_NOTIFICATION_REPLY"
                    component =
                        android.content.ComponentName(
                            this@ChatNotificationService,
                            NotificationReplyReceiver::class.java,
                        )
                    setPackage(packageName)
                    putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
                    profileName?.let {
                        putExtra(NotificationReplyReceiver.EXTRA_PROFILE_NAME, it)
                    }
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

        val notification = builder.build()
        if (replyGeneration != null) {
            ReplyNotificationTracker.postReplyNotification(
                context = this,
                notification = notification,
                generation = replyGeneration,
            )
        } else {
            ReplyNotificationTracker.postActionNotification(
                context = this,
                notification = notification,
            )
        }
    }

    private fun buildContentIntent(
        sessionId: String?,
        profileName: String? = null,
    ): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CHAT_FROM_NOTIFICATION
                component = android.content.ComponentName(this@ChatNotificationService, MainActivity::class.java)
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!sessionId.isNullOrBlank()) {
                    putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
                }
                if (!profileName.isNullOrBlank()) {
                    putExtra(NotificationReplyReceiver.EXTRA_PROFILE_NAME, profileName)
                }
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
    ): Int {
        val initialDecision =
            BackgroundConnectionPolicy.evaluate(BackgroundConnectionController.defaultSnapshot(isDeparting = true))
        val initialText = resolveNotificationText(initialDecision.notificationState)
        lifecycle.onStart(
            owner = this,
            promote = {
                startForeground(NOTIFICATION_ID, buildForegroundNotification(initialText))
            },
            // Do not remove foreground status before Android accepts retirement:
            // a newer start may already be queued, but not delivered to us yet.
            stopLatest = { stopSelfResult(startId) },
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        lifecycle.onDestroyed(this)
        eventCollector?.cancel()
        statusCollector?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startStatusCollection() {
        statusCollector =
            serviceScope.launch {
                launch {
                    NetworkMonitor.networkChanges.collect {
                        if (!isAppInForeground.get()) {
                            BackgroundConnectionController.default.reconcileState()
                        }
                    }
                }
                launch {
                    HermesWsClient.connectionStatus.collect {
                        if (!isAppInForeground.get()) {
                            BackgroundConnectionController.default.reconcileState()
                        }
                    }
                }
            }
    }

    internal fun updateNotification(state: BackgroundNotificationState) {
        if (isAppInForeground.get()) return
        val text = resolveNotificationText(state)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun resolveNotificationText(state: BackgroundNotificationState): String =
        when (state) {
            BackgroundNotificationState.WaitingForNetwork -> getString(R.string.notif_waiting_network)
            BackgroundNotificationState.Connecting -> getString(R.string.notif_connecting)
            BackgroundNotificationState.Reconnecting -> getString(R.string.notif_reconnecting)
            BackgroundNotificationState.WaitingForReplies -> getString(R.string.notif_waiting_replies)
            BackgroundNotificationState.ConnectedInBackground -> getString(R.string.notif_connected_in_background)
            BackgroundNotificationState.None -> getString(R.string.notif_waiting_replies)
        }
}

/**
 * Helper to start/stop the notification service from the UI layer.
 */
object NotificationHelper {
    fun start(context: Context) {
        val intent = Intent(context, ChatNotificationService::class.java)
        BackgroundConnectionController.default.onAppPause {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun stop(context: Context) {
        BackgroundConnectionController.default.onAppResume()
    }

    fun setAppForeground(
        context: Context,
        foreground: Boolean,
    ) {
        ChatNotificationService.setAppForeground(foreground)
        HermesWsClient.setAppForeground(foreground)
        if (!foreground) {
            BackgroundConnectionController.default.reconcileState()
        }
    }

    fun isAppInForeground(): Boolean = ChatNotificationService.isAppInForeground()
}
