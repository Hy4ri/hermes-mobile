package com.m57.hermescontrol.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong

data class ReplyNotificationTarget(
    val scopeId: String,
    val sessionId: String,
    val completionId: String,
    val generation: Long,
    val textSnippet: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val serverMessageId: Int? = null,
) {
    fun matches(
        candidateScopeId: String?,
        candidateSessionId: String?,
        candidateCompletionId: String?,
    ): Boolean {
        if (scopeId.isNotBlank() && scopeId != candidateScopeId) {
            return false
        }
        if (candidateSessionId.isNullOrBlank() || sessionId != candidateSessionId) {
            return false
        }
        if (candidateCompletionId.isNullOrBlank() || completionId.isBlank()) {
            return false
        }
        return candidateCompletionId == completionId
    }
}

internal data class ActiveReplyInfo(
    val id: Int,
    val kind: String?,
    val scopeId: String?,
    val sessionId: String?,
    val completionId: String?,
    val textSnippet: String?,
    val generation: Long,
    val timestamp: Long,
    val serverMessageId: Int? = null,
)

object ReplyNotificationTracker {
    const val EXTRA_NOTIF_KIND = "hermes_notif_kind"
    const val EXTRA_SCOPE_ID = "hermes_scope_id"
    const val EXTRA_SESSION_ID = "hermes_session_id"
    const val EXTRA_COMPLETION_ID = "hermes_completion_id"
    const val EXTRA_TEXT_SNIPPET = "hermes_text_snippet"
    const val EXTRA_GENERATION = "hermes_generation"
    const val EXTRA_SERVER_MESSAGE_ID = "hermes_server_message_id"

    const val KIND_REPLY = "reply"
    const val KIND_ACTION = "action"
    const val KIND_REPLIED = "replied"

    private val generationCounter = AtomicLong(0L)
    private val tombstoneGeneration = AtomicLong(0L)

    @Volatile
    private var activeTarget: ReplyNotificationTarget? = null

    private val defaultActiveNotificationProvider: (Context) -> ActiveReplyInfo? = { context ->
        runCatching {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val sbn =
                manager?.activeNotifications?.firstOrNull {
                    it.id == ChatNotificationService.PENDING_NOTIFICATION_ID
                }
            if (sbn != null) {
                val extras = sbn.notification?.extras
                ActiveReplyInfo(
                    id = sbn.id,
                    kind = extras?.getString(EXTRA_NOTIF_KIND),
                    scopeId = extras?.getString(EXTRA_SCOPE_ID),
                    sessionId = extras?.getString(EXTRA_SESSION_ID),
                    completionId = extras?.getString(EXTRA_COMPLETION_ID),
                    textSnippet = extras?.getString(EXTRA_TEXT_SNIPPET),
                    generation = extras?.getLong(EXTRA_GENERATION, 0L) ?: 0L,
                    timestamp = sbn.postTime,
                    serverMessageId = extras?.getInt(EXTRA_SERVER_MESSAGE_ID, -1)?.takeIf { it >= 0 },
                )
            } else {
                null
            }
        }.getOrNull()
    }

    internal var activeNotificationProvider: (Context) -> ActiveReplyInfo? = defaultActiveNotificationProvider

    fun nextGeneration(): Long {
        val floor = maxOf(generationCounter.get(), tombstoneGeneration.get())
        return generationCounter.updateAndGet { maxOf(it, floor) + 1 }
    }

    @Synchronized
    fun registerPendingReply(
        scopeId: String,
        sessionId: String,
        completionId: String,
        textSnippet: String,
        timestamp: Long = System.currentTimeMillis(),
        serverMessageId: Int? = null,
    ): Long {
        val generation = nextGeneration()
        activeTarget =
            ReplyNotificationTarget(
                scopeId = scopeId,
                sessionId = sessionId,
                completionId = completionId,
                generation = generation,
                textSnippet = textSnippet,
                timestamp = timestamp,
                serverMessageId = serverMessageId,
            )
        return generation
    }

    @Synchronized
    fun postReplyNotification(
        context: Context,
        notification: Notification,
        generation: Long,
    ): Boolean {
        if (generation <= tombstoneGeneration.get()) {
            return false
        }
        val current = activeTarget
        if (current != null && current.generation > generation) {
            return false
        }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
        manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, notification)
        return true
    }

    @Synchronized
    fun postActionNotification(
        context: Context,
        notification: Notification,
    ): Boolean {
        val nextGen = nextGeneration()
        tombstoneGeneration.updateAndGet { maxOf(it, nextGen) }
        activeTarget = null
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
        manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, notification)
        return true
    }

    @Synchronized
    fun postRepliedNotification(
        context: Context,
        notification: Notification,
    ): Boolean {
        val nextGen = nextGeneration()
        tombstoneGeneration.updateAndGet { maxOf(it, nextGen) }
        activeTarget = null
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
        manager.notify(ChatNotificationService.PENDING_NOTIFICATION_ID, notification)
        return true
    }

    @Synchronized
    fun onReplyNotificationPosted(
        scopeId: String,
        sessionId: String,
        completionId: String,
        textSnippet: String,
        generation: Long,
        timestamp: Long = System.currentTimeMillis(),
        serverMessageId: Int? = null,
    ) {
        if (generation <= tombstoneGeneration.get()) return
        generationCounter.updateAndGet { maxOf(it, generation) }
        activeTarget =
            ReplyNotificationTarget(
                scopeId = scopeId,
                sessionId = sessionId,
                completionId = completionId,
                generation = generation,
                textSnippet = textSnippet,
                timestamp = timestamp,
                serverMessageId = serverMessageId,
            )
    }

    @Synchronized
    fun onNonReplyNotificationPosted() {
        val nextGen = nextGeneration()
        tombstoneGeneration.updateAndGet { maxOf(it, nextGen) }
        activeTarget = null
    }

    @Synchronized
    fun getActiveTarget(context: Context? = null): ReplyNotificationTarget? {
        val inMemory = activeTarget
        if (inMemory != null) return inMemory
        return if (context != null) resolveTarget(context) else null
    }

    @Synchronized
    fun onMessageVisible(
        context: Context,
        scopeId: String?,
        sessionId: String?,
        completionId: String?,
    ): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val target = resolveTarget(context) ?: return false
        if (target.matches(scopeId, sessionId, completionId)) {
            return cancelReplyNotification(context, target.generation)
        }
        return false
    }

    @Synchronized
    fun cancelReplyNotification(
        context: Context,
        expectedGeneration: Long,
    ): Boolean {
        val target = resolveTarget(context)
        if (target != null && target.generation > expectedGeneration) {
            return false
        }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

        val activeInfo = activeNotificationProvider(context)
        if (activeInfo != null) {
            if (activeInfo.kind != KIND_REPLY) {
                activeTarget = null
                return false
            }
            if (activeInfo.generation <= tombstoneGeneration.get()) {
                activeTarget = null
                return false
            }
            if (activeInfo.generation > expectedGeneration) {
                return false
            }
        }

        tombstoneGeneration.updateAndGet { maxOf(it, expectedGeneration) }
        manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID)
        activeTarget = null
        return true
    }

    private fun resolveTarget(context: Context): ReplyNotificationTarget? {
        val inMemory = activeTarget
        if (inMemory != null) return inMemory

        val activeInfo = activeNotificationProvider(context) ?: return null
        if (activeInfo.kind != KIND_REPLY) return null
        generationCounter.updateAndGet { maxOf(it, activeInfo.generation) }
        if (activeInfo.generation <= tombstoneGeneration.get()) return null
        val sessionId = activeInfo.sessionId.orEmpty()
        if (sessionId.isBlank()) return null

        return ReplyNotificationTarget(
            scopeId = activeInfo.scopeId.orEmpty(),
            sessionId = sessionId,
            completionId = activeInfo.completionId.orEmpty(),
            generation = activeInfo.generation,
            textSnippet = activeInfo.textSnippet.orEmpty(),
            timestamp = activeInfo.timestamp,
            serverMessageId = activeInfo.serverMessageId,
        ).also { activeTarget = it }
    }

    @VisibleForTesting
    fun resetForTest() {
        activeTarget = null
        generationCounter.set(0L)
        tombstoneGeneration.set(0L)
        activeNotificationProvider = defaultActiveNotificationProvider
    }

    @VisibleForTesting
    fun setTargetForTest(target: ReplyNotificationTarget?) {
        activeTarget = target
    }
}
