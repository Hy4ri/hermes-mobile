package com.m57.hermescontrol.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.repository.KanbanRepositoryImpl
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

private const val MAX_MONITORED_BOARDS = 12

internal val KANBAN_BOARD_SLUG_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
internal val KANBAN_TASK_ID_PATTERN = Regex("t_[a-zA-Z0-9_-]{4,64}")

internal enum class KanbanAlertKind { COMPLETED, NEEDS_ATTENTION }

/** Notifications are for terminal or human-action events, never heartbeat noise. */
internal fun kanbanEventAlertKind(kind: String): KanbanAlertKind? =
    when (kind) {
        "completed" -> KanbanAlertKind.COMPLETED
        "blocked", "needs_input", "review_requested", "failed" -> KanbanAlertKind.NEEDS_ATTENTION
        else -> null
    }

/** Collision-free notification tag across boards. */
internal fun kanbanNotificationTag(
    board: String,
    taskId: String,
): String = "kanban:$board:$taskId"

/** Validate, de-duplicate, and cap untrusted board slugs from the dashboard API. */
internal fun selectKanbanAlertBoards(ids: List<String>): List<String> =
    ids
        .filter { KANBAN_BOARD_SLUG_PATTERN.matches(it) }
        .distinct()
        .take(MAX_MONITORED_BOARDS)

/** Board discovery uses the Kanban screen's authenticated REST client (token and cookie modes). */
internal suspend fun loadKanbanAlertBoards(repository: KanbanRepository): List<String> =
    when (val result = repository.getBoards(includeArchived = false)) {
        is NetworkResult.Success -> selectKanbanAlertBoards(result.data.boards.map { it.id })
        is NetworkResult.Failure -> emptyList()
    }

/**
 * Alerts on Kanban task completion / human-attention events while the app is
 * backgrounded. Watches every active board (not just the server-current one,
 * since the Kanban screen selects boards locally) with one bounded events
 * stream per board. Lives inside [ChatNotificationService], so it only runs
 * when the user's existing background-connection opt-in holds that service.
 *
 * Per server/board event cursors are persisted so reconnects never replay;
 * the very first connection to a board ignores historical events.
 */
internal class KanbanBackgroundAlertMonitor(
    private val context: Context,
    private val repository: KanbanRepository = KanbanRepositoryImpl(),
) {
    private val clients = CopyOnWriteArrayList<KanbanEventsClient>()
    private val prefs = context.getSharedPreferences("hermes_kanban_alert_cursors", Context.MODE_PRIVATE)
    private val startupEpochSeconds = System.currentTimeMillis() / 1000

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val boards = loadKanbanAlertBoards(repository)
                val endpoint = AuthManager.endpointForBuild().baseUrl.toString()
                boards.forEach { board ->
                    if (!scope.isActive) return@launch
                    connectBoard(scope, endpoint, board)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kanban background alerts unavailable: " + e.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        clients.forEach { it.disconnect() }
        clients.clear()
    }

    private fun connectBoard(
        scope: CoroutineScope,
        endpoint: String,
        board: String,
    ) {
        val key = "events_$endpoint|$board"
        var cursor = prefs.getLong(key, 0L)
        val firstConnection = !prefs.contains(key)
        val client = KanbanEventsClient()
        clients.add(client)
        client.connect(
            scope = scope,
            board = board,
            since = cursor,
            onEvents = { envelope ->
                envelope.events.sortedBy { it.id }.forEach { event ->
                    if (event.id <= cursor) return@forEach
                    cursor = event.id
                    prefs.edit().putLong(key, cursor).apply()
                    val kind = kanbanEventAlertKind(event.kind) ?: return@forEach
                    val taskId = event.taskId?.takeIf { KANBAN_TASK_ID_PATTERN.matches(it) } ?: return@forEach
                    if (firstConnection && (event.createdAt ?: 0) < startupEpochSeconds - 2) return@forEach
                    notifyTask(board, taskId, kind)
                }
            },
            onStatus = { status ->
                if (com.m57.hermescontrol.BuildConfig.DEBUG) Log.d(TAG, "Kanban $board alert stream: $status")
            },
        )
    }

    private fun notifyTask(
        board: String,
        taskId: String,
        kind: KanbanAlertKind,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_KANBAN_TASK_FROM_NOTIFICATION
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_KANBAN_BOARD, board)
                putExtra(MainActivity.EXTRA_KANBAN_TASK, taskId)
            }
        val tag = kanbanNotificationTag(board, taskId)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                tag.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val label =
            context.getString(
                when (kind) {
                    KanbanAlertKind.COMPLETED -> R.string.kanban_alert_completed
                    KanbanAlertKind.NEEDS_ATTENTION -> R.string.kanban_alert_needs_attention
                },
            )
        val text = "$taskId · $label · $board"
        val notification =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.kanban_alert_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(tag, 0, notification)
    }

    private companion object {
        const val TAG = "KanbanBackgroundAlerts"
    }
}
