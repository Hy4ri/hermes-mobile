package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HostedDriverStatus
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Gateway timestamps are Unix seconds, including fractional seconds, not milliseconds. */
internal fun hostedAgeSeconds(
    timestamp: Double?,
    nowMillis: Long,
): Long? {
    if (timestamp == null || !timestamp.isFinite() || timestamp < 0) return null
    return (nowMillis / 1_000.0 - timestamp).coerceAtLeast(0.0).toLong()
}

/** Collection owns the clock. Cancellation on leaving STARTED stops all ticks. */
internal fun hostedProgressClock(nowMillis: () -> Long = System::currentTimeMillis): Flow<Long> =
    flow {
        while (true) {
            emit(nowMillis())
            delay(1_000)
        }
    }

internal suspend fun collectHostedProgress(
    lifecycle: Lifecycle,
    nowMillis: () -> Long = System::currentTimeMillis,
    onTick: (Long) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        hostedProgressClock(nowMillis).collect(onTick)
    }
}

@Composable
internal fun HostedMemberProgress(
    driver: HostedDriverStatus?,
    owner: HostedOwner?,
    roomId: String?,
) {
    val activity = driver?.memberActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ticking =
        owner?.connected == true &&
            activity.orEmpty().any {
                it.status in setOf("running", "stopping") &&
                    (hostedAgeSeconds(it.startedAt, 0) != null || hostedAgeSeconds(it.progressAt, 0) != null)
            }
    // The identity includes timestamps: replacing an attempt resets the clock immediately.
    val now by produceState(System.currentTimeMillis(), lifecycle, owner, roomId, activity, ticking) {
        value = System.currentTimeMillis()
        if (ticking) {
            collectHostedProgress(lifecycle) { value = it }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        driver?.maxRounds?.takeIf { it > 0 }?.let {
            Text(stringResource(R.string.hosted_round_limit, it), style = MaterialTheme.typography.labelMedium)
        }
        HostedRoomAttentionLine(driver)
        if (activity.isNullOrEmpty()) {
            Text(
                stringResource(
                    if (activity == null) R.string.hosted_activity_unavailable else R.string.hosted_activity_empty,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activity.orEmpty().forEach { member ->
            Column {
                Text(member.memberId, style = MaterialTheme.typography.labelLarge)
                Text(
                    when (member.status) {
                        "queued" -> stringResource(R.string.hosted_activity_queued)
                        "running" -> stringResource(R.string.hosted_activity_running)
                        "stopping" -> stringResource(R.string.hosted_activity_stopping)
                        else -> member.status
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                member.roundIndex?.takeIf { it >= 0 && it < Int.MAX_VALUE }?.let { round ->
                    val maximum = driver?.maxRounds?.takeIf { it > 0 }
                    Text(
                        if (maximum == null) {
                            stringResource(R.string.hosted_round, round + 1)
                        } else {
                            stringResource(R.string.hosted_round_of, round + 1, maximum)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                member.threadId?.takeIf(String::isNotBlank)?.let {
                    Text(stringResource(R.string.hosted_thread, it), style = MaterialTheme.typography.bodySmall)
                }
                member.tool?.takeIf(String::isNotBlank)?.let {
                    Text(stringResource(R.string.hosted_tool, it), style = MaterialTheme.typography.bodySmall)
                }
                if (member.status in setOf("running", "stopping")) {
                    hostedAgeSeconds(member.startedAt, now)?.let {
                        Text(stringResource(R.string.hosted_elapsed, it), style = MaterialTheme.typography.bodySmall)
                    }
                    hostedAgeSeconds(member.progressAt, now)?.let {
                        Text(
                            stringResource(R.string.hosted_progress_age, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
