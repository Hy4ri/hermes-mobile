package com.m57.hermescontrol.ui.bots.group

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HostedDriverStatus

/** What a hosted room is waiting on, mirroring the Desktop group header. */
internal sealed interface HostedRoomAttention {
    data object Blocked : HostedRoomAttention

    data class Approvals(
        val count: Int,
    ) : HostedRoomAttention

    data class NeedsAttention(
        val count: Int,
    ) : HostedRoomAttention
}

/**
 * The room's wait state: a block, approvals, and every other pending action. A gateway whose
 * driver is down sends no status at all; the room already says its driver is unavailable.
 */
internal fun hostedRoomAttention(driver: HostedDriverStatus?): List<HostedRoomAttention> {
    if (driver == null) return emptyList()
    val pending = driver.pendingActions
    val approvals = pending.count { it.kind == "approval" }
    // The gateway also calls a room blocked while a stopped task settles: that is Stop at work,
    // not a block, unless an unresolved task holds the room as well.
    val blocked =
        driver.blocked &&
            (driver.counts["indeterminate"].orZero() > 0 || driver.counts["stopping"].orZero() == 0)
    return buildList {
        if (blocked) add(HostedRoomAttention.Blocked)
        if (approvals > 0) add(HostedRoomAttention.Approvals(approvals))
        val other = pending.size - approvals
        if (other > 0) add(HostedRoomAttention.NeedsAttention(other))
    }
}

private fun Int?.orZero(): Int = this ?: 0

/** Shown beside the roster, never instead of it: one failed member does not hide the others. */
@Composable
internal fun HostedRoomAttentionLine(
    driver: HostedDriverStatus?,
    modifier: Modifier = Modifier,
) {
    val parts = hostedRoomAttention(driver)
    if (parts.isEmpty()) return
    val labels = ArrayList<String>(parts.size)
    for (part in parts) {
        labels +=
            when (part) {
                HostedRoomAttention.Blocked -> stringResource(R.string.hosted_attention_blocked)
                is HostedRoomAttention.Approvals -> stringResource(R.string.hosted_attention_approvals, part.count)
                is HostedRoomAttention.NeedsAttention -> stringResource(R.string.hosted_attention_other, part.count)
            }
    }
    Text(
        text = labels.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("hosted_room_attention"),
    )
}
