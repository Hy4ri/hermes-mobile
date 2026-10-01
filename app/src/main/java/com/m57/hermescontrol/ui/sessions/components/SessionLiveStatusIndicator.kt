package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val (badgeType, textRes, descRes) =
        when (liveStatus) {
            SessionLiveStatus.WORKING -> {
                Triple(
                    StatusBadgeType.SUCCESS,
                    R.string.sessions_live_status_running,
                    R.string.sessions_live_status_running_desc,
                )
            }

            SessionLiveStatus.WAITING -> {
                Triple(
                    StatusBadgeType.WARNING,
                    R.string.sessions_live_status_waiting,
                    R.string.sessions_live_status_waiting_desc,
                )
            }
        }

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
            when (liveStatus) {
                SessionLiveStatus.WORKING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = fgColor,
                    )
                }

                SessionLiveStatus.WAITING -> {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = fgColor,
                        modifier = Modifier.size(12.dp),
                    )
                }
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
