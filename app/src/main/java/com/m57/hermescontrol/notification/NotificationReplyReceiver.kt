package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.launch

open class NotificationReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    /**
     * Test-friendly wrapper for [BroadcastReceiver.goAsync] which is `final`
     * (Java) and cannot be mocked or overridden directly. Tests override this
     * via anonymous subclass to inject a fake [PendingResult].
     */
    internal open fun goAsyncCompat(): BroadcastReceiver.PendingResult = goAsync()

    /**
     * Test-friendly wrapper for notification creation. Override in tests to
     * avoid [NotificationCompat.Builder.build()] calling Android framework
     * methods that throw "not mocked" in unit tests.
     */
    internal open fun buildReplyNotification(context: Context): Notification =
        NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes")
            .setContentText("Replied")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        if (!replyText.isNullOrBlank() && !sessionId.isNullOrBlank()) {
            val pendingResult = goAsyncCompat()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    kotlinx.coroutines.withTimeout(5000L) {
                        val db =
                            com.m57.hermescontrol.data.local.HermesDatabase
                                .get(context)
                        val dao = db.chatMessageDao()

                        if (!dao.sessionExists(sessionId)) {
                            android.util.Log.w("NotificationReply", "Ignoring reply for unknown session: $sessionId")
                            return@withTimeout
                        }

                        HermesWsClient.sendMessage(sessionId, replyText)

                        val entity =
                            com.m57.hermescontrol.data.local.ChatMessageEntity(
                                id =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                sessionId = sessionId,
                                role = "USER",
                                content = replyText,
                                timestamp = System.currentTimeMillis(),
                            )
                        dao.upsert(entity)

                        val repliedNotification = buildReplyNotification(context)
                        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, repliedNotification)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NotificationReply", "Failed to process reply", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
