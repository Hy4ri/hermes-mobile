package com.m57.hermescontrol.ui.commands

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class QuickCommand(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@Composable
fun CommandPanel(
    onStatusClick: () -> Unit,
    onSessionsClick: () -> Unit,
    onSystemStatsClick: () -> Unit,
    onNewSessionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val commands =
        listOf(
            QuickCommand("Status", Icons.Filled.Info, onStatusClick),
            QuickCommand("Sessions", Icons.Filled.History, onSessionsClick),
            QuickCommand("System Stats", Icons.Filled.Analytics, onSystemStatsClick),
            QuickCommand("New Session", Icons.Filled.Add, onNewSessionClick),
        )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Quick Commands",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                commands.forEach { cmd ->
                    AssistChip(
                        onClick = cmd.action,
                        label = { Text(cmd.label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = {
                            Icon(
                                imageVector = cmd.icon,
                                contentDescription = cmd.label,
                                modifier = Modifier.padding(0.dp),
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                    )
                }
            }
        }
    }
}
