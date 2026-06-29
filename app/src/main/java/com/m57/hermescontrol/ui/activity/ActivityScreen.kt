package com.m57.hermescontrol.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.model.ActivityItem
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import java.time.Duration
import java.time.Instant

@Composable
fun ActivityScreen(
    onOpenDrawer: () -> Unit = {},
    sessionId: String? = null,
    vm: ActivityViewModel = viewModel(),
) {
    val items by vm.items.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val error by vm.error.collectAsState()

    HermesScaffold(
        title = { Text("Activity") },
        navigationIcon = NavIcon.Menu(onOpen = onOpenDrawer),
        actions = {
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        },
    ) { padding ->
        when {
            isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
            error != null -> {
                ErrorState(
                    message = error ?: "Unknown error",
                    onRetry = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            items.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.Notifications,
                    title = "No activity yet",
                    subtitle = "Cron job deliveries will appear here when they run.",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(items, key = { it.id }) { item ->
                        ActivityCard(item = item)
                        Spacer(Modifier.height(8.dp))
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(item: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.statusIcon(),
                contentDescription = null,
                tint = item.statusColor(),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.lastRunAt?.let { time ->
                    Text(
                        text = formatRelativeTime(time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun ActivityItem.statusIcon(): ImageVector =
    when (status) {
        "ok" -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Notifications
    }

@Composable
private fun ActivityItem.statusColor(): Color =
    when (status) {
        "ok" -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }

private fun formatRelativeTime(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        val now = Instant.now()
        val duration = Duration.between(instant, now)
        when {
            duration.isNegative -> "just now"
            duration.seconds < 60 -> "just now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
            duration.toHours() < 24 -> "${duration.toHours()}h ago"
            duration.toDays() < 7 -> "${duration.toDays()}d ago"
            else -> "${duration.toDays() / 7}w ago"
        }
    } catch (e: Exception) {
        isoTimestamp
    }
}
