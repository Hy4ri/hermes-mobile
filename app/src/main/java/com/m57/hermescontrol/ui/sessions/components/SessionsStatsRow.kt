package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.sessions.HistorySection
import com.m57.hermescontrol.ui.sessions.formatCompactCount

@Composable
fun SessionsStatsRow(
    total: Int,
    loadedMessageCount: Int,
    section: HistorySection,
    spacing: Spacing,
    statusColors: HermesStatusColors,
    onPruneClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        StatCard(
            label = stringResource(R.string.sessions_stat_total),
            value = formatCompactCount(total),
            icon = Icons.Filled.History,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatCard(
            label = stringResource(R.string.sessions_stat_messages_loaded),
            value = formatCompactCount(loadedMessageCount),
            icon = Icons.Filled.Email,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (section == HistorySection.CONVERSATIONS) {
            Card(
                modifier = Modifier.fillMaxHeight(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                onClick = onPruneClick,
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = statusColors.warning,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.sessions_action_prune),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.warning,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
