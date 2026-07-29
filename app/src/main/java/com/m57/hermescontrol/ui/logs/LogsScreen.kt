package com.m57.hermescontrol.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import kotlinx.coroutines.launch

private data class LogsFilterOption(
    val value: String,
    val label: String,
)

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
    var filters by remember { mutableStateOf(LogsFilters()) }
    var pauseScroll by remember { mutableStateOf(false) }

    // Server-side filter options, mirroring the desktop LogsPage toolbar
    // (files: agent/errors/gateway, levels: ALL/DEBUG/INFO/WARNING/ERROR,
    // components: gateway/agent/tools/cli/cron, lines: 50-500).
    val fileOptions =
        listOf(
            LogsFilterOption("agent", stringResource(R.string.logs_filter_agent)),
            LogsFilterOption("errors", stringResource(R.string.logs_filter_errors)),
            LogsFilterOption("gateway", stringResource(R.string.logs_filter_gateway)),
        )
    val levelOptions =
        listOf(
            LogsFilterOption("ALL", stringResource(R.string.logs_filter_all)),
            LogsFilterOption("DEBUG", stringResource(R.string.logs_filter_debug)),
            LogsFilterOption("INFO", stringResource(R.string.logs_filter_info)),
            LogsFilterOption("WARNING", stringResource(R.string.logs_filter_warning)),
            LogsFilterOption("ERROR", stringResource(R.string.logs_filter_error)),
        )
    val componentOptions =
        listOf(
            LogsFilterOption("all", stringResource(R.string.logs_filter_all)),
            LogsFilterOption("gateway", stringResource(R.string.logs_filter_gateway)),
            LogsFilterOption("agent", stringResource(R.string.logs_filter_agent)),
            LogsFilterOption("tools", stringResource(R.string.logs_filter_tools)),
            LogsFilterOption("cli", stringResource(R.string.logs_filter_cli)),
            LogsFilterOption("cron", stringResource(R.string.logs_filter_cron)),
        )
    val lineOptions = listOf("50", "100", "200", "500").map { LogsFilterOption(it, it) }

    // Client-side free-text filter over the server-returned lines
    val filteredLogs =
        remember(query, state.logs) {
            if (query.isBlank()) {
                state.logs
            } else {
                state.logs.filter { it.contains(query, ignoreCase = true) }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    // Auto-refresh logs via polling while the screen is visible
    LifecycleStartEffect(viewModel) {
        viewModel.startAutoRefresh()
        onStopOrDispose { viewModel.stopAutoRefresh() }
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // Auto-scroll to bottom when new logs arrive (unless paused)
    LaunchedEffect(filteredLogs.size, pauseScroll) {
        if (!pauseScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(Int.MAX_VALUE)
        }
    }

    // Build the export text from filtered logs
    val exportText =
        remember(filteredLogs) {
            filteredLogs.joinToString("\n")
        }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_logs)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadLogs() },
        actions = {
            // Pause / Resume toggle
            IconButton(onClick = { pauseScroll = !pauseScroll }) {
                Icon(
                    imageVector = if (pauseScroll) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription =
                        stringResource(
                            if (pauseScroll) R.string.logs_action_resume else R.string.logs_action_pause,
                        ),
                )
            }
            // Share / Export
            IconButton(
                onClick = {
                    shareLogs(context, exportText)
                },
                enabled = filteredLogs.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.logs_action_export),
                )
            }
        },
    ) {
        when {
            state.isLoading && state.logs.isEmpty() -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadLogs() },
                )
            }

            state.logs.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.logs_empty_title),
                    subtitle = stringResource(R.string.logs_empty_desc),
                    icon = Icons.Filled.HistoryEdu,
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
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
                        // Search bar
                        item {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = "Search logs...",
                                modifier = Modifier.padding(bottom = spacing.xs),
                            )
                        }

                        // Server-side filter chips
                        item {
                            FilterChipRow(
                                options = fileOptions,
                                selected = filters.file,
                                onSelected = { viewModel.setFilters(filters.copy(file = it)) },
                                modifier = Modifier.padding(bottom = spacing.xs),
                            )
                        }
                        item {
                            FilterChipRow(
                                options = levelOptions,
                                selected = filters.level,
                                onSelected = { viewModel.setFilters(filters.copy(level = it)) },
                                modifier = Modifier.padding(bottom = spacing.xs),
                            )
                        }
                        item {
                            FilterChipRow(
                                options = componentOptions,
                                selected = filters.component,
                                onSelected = { viewModel.setFilters(filters.copy(component = it)) },
                                modifier = Modifier.padding(bottom = spacing.xs),
                            )
                        }
                        item {
                            FilterChipRow(
                                options = lineOptions,
                                selected = filters.lines.toString(),
                                onSelected = { viewModel.setFilters(filters.copy(lines = it.toInt())) },
                                modifier = Modifier.padding(bottom = spacing.sm),
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

                    // Jump-to-bottom FAB — only when paused and scrolled up
                    if (pauseScroll && filteredLogs.isNotEmpty()) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(filteredLogs.size)
                                }
                            },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardDoubleArrowDown,
                                contentDescription =
                                    stringResource(R.string.logs_content_desc_jump_bottom),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    options: List<LogsFilterOption>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = selected == option.value
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(option.value) },
                label = { Text(option.label) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

private fun shareLogs(
    context: Context,
    text: String,
) {
    // Also copy to clipboard
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Hermes Logs", text))

    val sendIntent =
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
    context.startActivity(Intent.createChooser(sendIntent, "Share logs"))
}
