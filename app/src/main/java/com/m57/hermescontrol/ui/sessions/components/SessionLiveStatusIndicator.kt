package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.statusBadgeColors

@Composable
fun SessionLiveStatusIndicator(
    liveStatus: SessionLiveStatus,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current

    val badgeType = liveStatus.badgeType()
    val (textRes, descRes) = liveStatus.labels()

    val (bgColor, fgColor) = requireNotNull(statusBadgeColors(badgeType, statusColors))
    val text = stringResource(textRes)
    val description = stringResource(descRes)

    Surface(
        modifier =
            modifier
                .testTag("session_live_status_$sessionId")
                .semantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = spacing.sm,
                    vertical = 2.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (liveStatus == SessionLiveStatus.WORKING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = fgColor,
                )
            } else {
                Icon(
                    imageVector = liveStatus.icon(),
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Issue #1642: color tells urgency, the label tells the reason. Every "needs you" state shares the
 * warning color; Done is info (blue), Failed is error (red).
 */
internal fun SessionLiveStatus.badgeType(): StatusBadgeType =
    when (this) {
        SessionLiveStatus.WORKING -> StatusBadgeType.SUCCESS

        SessionLiveStatus.WAITING,
        SessionLiveStatus.WAITING_APPROVAL,
        SessionLiveStatus.WAITING_CLARIFY,
        -> StatusBadgeType.WARNING

        SessionLiveStatus.DONE -> StatusBadgeType.INFO

        SessionLiveStatus.FAILED -> StatusBadgeType.ERROR
    }

/** (label, accessibility description) string resources. */
private fun SessionLiveStatus.labels(): Pair<Int, Int> =
    when (this) {
        SessionLiveStatus.WORKING -> {
            R.string.sessions_live_status_running to R.string.sessions_live_status_running_desc
        }

        SessionLiveStatus.WAITING -> {
            R.string.sessions_live_status_waiting to R.string.sessions_live_status_waiting_desc
        }

        SessionLiveStatus.WAITING_APPROVAL -> {
            R.string.sessions_live_status_approval to
                R.string.sessions_live_status_approval_desc
        }

        SessionLiveStatus.WAITING_CLARIFY -> {
            R.string.sessions_live_status_clarify to
                R.string.sessions_live_status_clarify_desc
        }

        SessionLiveStatus.DONE -> {
            R.string.sessions_live_status_done to R.string.sessions_live_status_done_desc
        }

        SessionLiveStatus.FAILED -> {
            R.string.sessions_live_status_failed to R.string.sessions_live_status_failed_desc
        }
    }

private fun SessionLiveStatus.icon(): ImageVector =
    when (this) {
        SessionLiveStatus.DONE -> Icons.Filled.CheckCircle
        SessionLiveStatus.FAILED -> Icons.Filled.Error
        else -> Icons.Filled.Warning
    }
