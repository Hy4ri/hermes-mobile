package com.m57.hermescontrol.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect

@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: LogsViewModel = viewModel { LogsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }

    val filteredLogs =
        remember(query, state.logs) {
            state.logs.filter { logLine ->
                logLine.contains(query, ignoreCase = true)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // Auto scroll to bottom when new logs arrive
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size)
        }
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_logs)) },
        navigationIcon = NavIcon.Menu(onOpenDrawer),
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadLogs() },
    ) {
        when {
            state.isLoading && state.logs.isEmpty() -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadLogs() },
                )
            }

            state.logs.isEmpty() -> {
                // B5 (Jun 18 2026, kanban t_2322818d): proper empty-state using
                // the shared EmptyState component instead of a bare Text.
                EmptyState(
                    title = stringResource(R.string.logs_empty_title),
                    subtitle = stringResource(R.string.logs_empty_desc),
                    icon = Icons.Filled.HistoryEdu,
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentPadding =
                        PaddingValues(
                            horizontal = spacing.md,
                            vertical = spacing.sm,
                        ),
                ) {
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search logs...",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(filteredLogs) { logLine ->
                        Text(
                            text = logLine,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
