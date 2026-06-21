package com.m57.hermescontrol.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SessionsViewModel = viewModel { SessionsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_history)) },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadSessions() },
        modifier = modifier,
    ) {
        when {
            state.isLoading && state.sessions.isEmpty() -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadSessions() },
                )
            }

            state.sessions.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    subtitle = stringResource(R.string.history_empty_desc),
                    icon = Icons.Filled.History,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.sessions) { session ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        NavigationController.pendingSessionId = session.id
                                        NavigationController.navigateTo(ChatScreen)
                                    },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                            ) {
                                Text(
                                    text = session.title ?: stringResource(R.string.history_untitled),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(spacing.xs))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.history_message_count,
                                                session.message_count ?: 0,
                                            ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!session.status.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.width(spacing.sm))
                                        Text(
                                            text = "•  ${session.status}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
