package com.m57.hermescontrol.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.launch

class NotificationReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        if (!replyText.isNullOrBlank() && !sessionId.isNullOrBlank()) {
            HermesWsClient.sendMessage(sessionId, replyText)

            val pendingResult = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val db = com.m57.hermescontrol.data.local.HermesDatabase.get(context)
                    val dao = db.chatMessageDao()
                    val entity = com.m57.hermescontrol.data.local.ChatMessageEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "USER",
                        content = replyText,
                        timestamp = System.currentTimeMillis(),
                    )
                    dao.upsert(entity)
                } catch (e: Exception) {
                    android.util.Log.e("NotificationReply", "Failed to save replied message to Room DB", e)
                } finally {
                    pendingResult.finish()
                }
            }

            val repliedNotification =
                NotificationCompat
                    .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Hermes")
                    .setContentText("Replied")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, repliedNotification)
        }
    }
}
