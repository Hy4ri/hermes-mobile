package com.m57.hermescontrol.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.profilelock.ProfileLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

private const val TAG = "CronBackgroundAlerts"
private const val BACKSTOP_MS = 60_000L
private const val READ_TIMEOUT_MS = 10_000L
private const val RECHECK_DELAY_MS = 500L

internal enum class CronRunOutcome { Completed, Failed, DeliveryFailed }

internal data class CronRunAlert(
    val jobId: String,
    val name: String,
    val outcome: CronRunOutcome,
)

/** last_run_at is the scheduler's terminal timestamp, not the job's current state or next run. */
internal class CronCompletionTracker {
    private val latestByJob = mutableMapOf<String, Instant>()
    private var hasBaseline = false

    fun observe(jobs: List<CronJob>): List<CronRunAlert> {
        val canAlert = hasBaseline
        hasBaseline = true
        return jobs.mapNotNull { job ->
            if (job.id.isBlank() || job.id == "unknown") return@mapNotNull null
            val stamp = parseCompletionTime(job.last_run_at) ?: return@mapNotNull null
            if (latestByJob[job.id]?.let { stamp <= it } == true) return@mapNotNull null
            latestByJob[job.id] = stamp
            if (!canAlert) return@mapNotNull null
            val outcome =
                when (job.lastRunStatus) {
                    "ok" -> CronRunOutcome.Completed
                    "error" -> CronRunOutcome.Failed
                    "delivery_failed" -> CronRunOutcome.DeliveryFailed
                    else -> return@mapNotNull null
                }
            CronRunAlert(job.id, job.name.ifBlank { job.id }, outcome)
        }
    }

    private fun parseCompletionTime(value: String?): Instant? =
        try {
            value?.let(Instant::parse)
        } catch (_: DateTimeParseException) {
            null
        }
}

internal suspend fun watchCronCompletions(
    refreshes: Flow<Unit>,
    canMonitor: () -> Boolean,
    isConnected: () -> Boolean,
    loadJobs: suspend () -> List<CronJob>?,
    notify: (CronRunAlert) -> Unit,
) {
    var tracker = CronCompletionTracker()
    refreshes.conflate().collect {
        if (!canMonitor()) {
            tracker = CronCompletionTracker()
            return@collect
        }
        if (!isConnected()) return@collect
        val jobs = loadJobs()
        currentCoroutineContext().ensureActive()
        if (!canMonitor()) {
            tracker = CronCompletionTracker()
            return@collect
        }
        if (jobs == null || !isConnected()) return@collect
        tracker.observe(jobs).forEach { alert ->
            if (canMonitor()) notify(alert)
        }
    }
}

internal fun startCronBackgroundAlerts(
    context: Context,
    scope: CoroutineScope,
) {
    scope.launch {
        AuthManager.dataScopeFlow.collectLatest { owner ->
            owner ?: return@collectLatest
            watchCronCompletions(
                refreshes = cronRefreshes(),
                canMonitor = {
                    AuthManager.currentDataScope() == owner &&
                        !ChatNotificationService.isAppInForeground() &&
                        AuthManager.isNotifySessionCompletions() &&
                        ProfileLock.allows(CronJobsScreen)
                },
                isConnected = { HermesWsClient.connectionStatus.value == ConnectionStatus.CONNECTED },
                loadJobs = { loadCronJobs(owner) },
                notify = { postCronAlert(context, owner, it) },
            )
        }
    }
}

private fun cronRefreshes(): Flow<Unit> =
    merge(
        ChangeEventHub.events.filter { it.type == ChangeEvents.CRON }.map { Unit },
        HermesWsClient.connectionStatus.map { Unit },
        // The watcher observes one server home; reconnect and poll even when no signal arrived for this profile.
        flow {
            while (true) {
                delay(BACKSTOP_MS)
                emit(Unit)
            }
        },
    )

private suspend fun loadCronJobs(owner: DataScope): List<CronJob>? {
    // jobs.json also moves for claim/heartbeat writes. Briefly coalesce a burst around a run.
    delay(RECHECK_DELAY_MS)
    if (AuthManager.currentDataScope() != owner) return null
    return when (
        val result =
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                safeApiRead(retries = 0) { ApiClient.hermesApi.getCronJobs(profile = owner.activeProfileId) }
            }
    ) {
        is NetworkResult.Success -> {
            result.data
        }

        else -> {
            Log.d(TAG, "Cron job refresh unavailable")
            null
        }
    }
}

private fun postCronAlert(
    context: Context,
    owner: DataScope,
    alert: CronRunAlert,
) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val openCron =
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CRON_FROM_NOTIFICATION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val openIntent =
        PendingIntent.getActivity(context, 0, openCron, PendingIntent.FLAG_IMMUTABLE)
    val body =
        context.getString(
            when (alert.outcome) {
                CronRunOutcome.Completed -> R.string.notif_cron_completed
                CronRunOutcome.Failed -> R.string.notif_cron_failed
                CronRunOutcome.DeliveryFailed -> R.string.notif_cron_delivery_failed
            },
        )
    val notification =
        NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                alert.name
                    .lineSequence()
                    .first()
                    .take(120),
            ).setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
    manager.notify("cron:${owner.connectionProfileId}:${owner.activeProfileId}:${alert.jobId}", 0, notification)
}
