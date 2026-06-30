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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect
import kotlinx.coroutines.launch

private enum class LogSeverity {
    ERROR,
    WARN,
    INFO,
    DEBUG,
}

private fun detectSeverity(line: String): LogSeverity? {
    val upper = line.uppercase()
    return when {
        Regex("\\[ERROR\\]|\\bERROR\\b|\\|ERROR\\||^ERROR[\\s:]").containsMatchIn(upper) ->
            LogSeverity.ERROR
        Regex("\\[WARN(?:ING)?\\]|\\bWARN\\b|\\|WARN(?:ING)?\\||^WARN(?:ING)?[\\s:]").containsMatchIn(upper) ->
            LogSeverity.WARN
        Regex("\\[INFO\\]|\\bINFO\\b|\\|INFO\\||^INFO[\\s:]").containsMatchIn(upper) ->
            LogSeverity.INFO
        Regex("\\[DEBUG\\]|\\bDEBUG\\b|\\|DEBUG\\||^DEBUG[\\s:]").containsMatchIn(upper) ->
            LogSeverity.DEBUG
        else -> null
    }
}

private val LogSeverity.labelRes: Int
    get() =
        when (this) {
            LogSeverity.ERROR -> R.string.logs_filter_error
            LogSeverity.WARN -> R.string.logs_filter_warn
            LogSeverity.INFO -> R.string.logs_filter_info
            LogSeverity.DEBUG -> R.string.logs_filter_debug
        }

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
    var severityFilter by remember { mutableStateOf<LogSeverity?>(null) }
    var pauseScroll by remember { mutableStateOf(false) }

    // Build severity-indexed sets for fast lookup
    val severityMap =
        remember(state.logs) {
            state.logs.map { line -> detectSeverity(line) }
        }

    // Filter by query + severity
    val filteredLogs =
        remember(query, severityFilter, state.logs) {
            state.logs.filterIndexed { index, logLine ->
                val matchesQuery = logLine.contains(query, ignoreCase = true)
                val matchesSeverity =
                    severityFilter == null || severityMap.getOrNull(index) == severityFilter
                matchesQuery && matchesSeverity
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    // Auto-refresh logs via polling while the screen is visible
    DisposableEffect(Unit) {
        viewModel.startAutoRefresh()
        onDispose { viewModel.stopAutoRefresh() }
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
                LoadingState()
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

                        // Severity filter chips
                        item {
                            SeverityFilterRow(
                                selected = severityFilter,
                                onSelected = { severityFilter = it },
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
private fun SeverityFilterRow(
    selected: LogSeverity?,
    onSelected: (LogSeverity?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val chipColors = FilterChipDefaults.filterChipColors()

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "All" chip
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.logs_filter_all)) },
            colors = chipColors,
        )

        LogSeverity.entries.forEach { severity ->
            val color =
                when (severity) {
                    LogSeverity.ERROR -> statusColors.error
                    LogSeverity.WARN -> statusColors.warning
                    LogSeverity.INFO -> statusColors.info
                    LogSeverity.DEBUG -> statusColors.success
                }
            FilterChip(
                selected = selected == severity,
                onClick = {
                    onSelected(if (selected == severity) null else severity)
                },
                label = {
                    Text(
                        text = stringResource(severity.labelRes),
                        color = if (selected == severity) color else MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon =
                    if (selected == severity) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = color,
                            )
                        }
                    } else {
                        null
                    },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.12f),
                    ),
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
