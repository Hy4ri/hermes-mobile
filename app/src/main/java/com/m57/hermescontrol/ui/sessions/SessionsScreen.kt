package com.m57.hermescontrol.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    onSwitchToSession: ((sessionId: String, sessionTitle: String?) -> Unit)? = null,
    viewModel: SessionsViewModel = viewModel { SessionsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = { Text("Sessions") },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadSessions() },
    ) { paddingValues ->
        when {
            state.isLoading && state.sessions.isEmpty() -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }
            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadSessions() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            state.sessions.isEmpty() -> {
                EmptyState(
                    title = "No sessions yet",
                    subtitle = "Start a conversation to create a session.",
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
                LazyColumn(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = {
                                onSwitchToSession?.invoke(session.id, session.title)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = session.title ?: "Untitled Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // ID + status
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.id.take(12) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    session.status?.let { status ->
                        StatusBadge(
                            text = status,
                            status =
                                when (status.lowercase()) {
                                    "active" -> StatusBadgeType.SUCCESS
                                    "paused" -> StatusBadgeType.WARNING
                                    else -> StatusBadgeType.NEUTRAL
                                },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Metadata row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    session.message_count?.let { count ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$count messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    session.created_at?.let { date ->
                        Text(
                            text = date.take(10), // YYYY-MM-DD
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
