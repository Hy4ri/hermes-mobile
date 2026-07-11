package com.m57.hermescontrol.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Maps a session source string to a Material icon for visual identification.
 */
private fun sourceIcon(source: String?): ImageVector? =
    when (source?.lowercase()) {
        "telegram", "tg" -> Icons.Filled.Send
        "web", "dashboard" -> Icons.Filled.Language
        "api", "rest" -> Icons.Filled.Code
        "cli", "terminal" -> Icons.Filled.Terminal
        else -> null
    }

/**
 * Maps a source string to a label for tooltip / accessibility.
 */
private fun sourceLabel(source: String?): String =
    when (source?.lowercase()) {
        "telegram", "tg" -> "Telegram"
        "web", "dashboard" -> "Web"
        "api", "rest" -> "API"
        "cli", "terminal" -> "CLI"
        else -> source ?: "Unknown"
    }

/**
 * Builds an annotated string with search term highlighting.
 */
private fun highlightText(
    text: String,
    query: String,
    highlightBackground: Color,
    highlightForeground: Color,
): AnnotatedString =
    buildAnnotatedString {
        if (query.isBlank()) {
            append(text)
            return@buildAnnotatedString
        }
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }
            withStyle(
                SpanStyle(
                    background = highlightBackground,
                    color = highlightForeground,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            currentIndex = matchIndex + query.length
        }
    }

/**
 * Converts a backend search hit into a display model WITHOUT faking a title.
 * The backend returns no session name, only a matched `snippet` + metadata, so the
 * card must render the snippet as a match excerpt (never as the title). `title` is
 * deliberately left null so [SearchResultCard] shows the honest "Match" layout.
 */
private fun SessionSearchResult.toSessionInfo(): SessionInfo =
    SessionInfo(
        id = session_id,
        title = null,
        preview = snippet,
        source = source,
        model = model,
        started_at = session_started,
        // message_count/status aren't in the search payload; leave null so the
        // search card hides those normal-list affordances.
    )

/**
 * Formats a backend epoch-seconds timestamp into a friendly, local relative/absolute
 * string. Used for search results where the session name is unknown.
 */
private fun formatPlayedAt(epochSeconds: Double?): String? {
    if (epochSeconds == null || epochSeconds <= 0.0) return null
    val instant =
        try {
            Instant.ofEpochSecond(epochSeconds.toLong())
        } catch (_: Exception) {
            return null
        }
    val now = Instant.now()
    val diffSec = abs(now.epochSecond - instant.epochSecond)
    val absFormatter =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        diffSec < 7 * 86400 -> "${diffSec / 86400}d ago"
        else -> absFormatter.format(instant)
    }
}

/**
 * Builds the list of sessions to display. In search mode the backend results are
 * used; otherwise the locally-paginated list is filtered by title/status as before.
 */
private fun displayedSessions(state: SessionsUiState): List<SessionInfo> =
    if (state.isSearchMode) {
        state.searchResults.map { it.toSessionInfo() }
    } else {
        state.sessions.filter { session ->
            session.title?.contains(state.searchQuery, ignoreCase = true) == true ||
                session.status?.contains(state.searchQuery, ignoreCase = true) == true
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SessionsViewModel = viewModel { SessionsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    var pruneDays by remember { mutableStateOf("7") }

    val sessionsToDisplay =
        remember(
            state.isSearchMode,
            state.searchQuery,
            state.sessions,
            state.searchResults,
        ) {
            displayedSessions(state)
        }

    val hasSelection = state.selectedIds.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
        viewModel.loadStats()
    }

    // Toast effect
    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = { viewModel.clearToast() },
    )

    // Prune dialog
    if (state.showPruneDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hidePruneDialog() },
            title = { Text(stringResource(R.string.sessions_prune_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sessions_prune_desc))
                    Spacer(modifier = Modifier.height(spacing.md))
                    OutlinedTextField(
                        value = pruneDays,
                        onValueChange = { pruneDays = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.sessions_prune_days_label)) },
                        placeholder = { Text("7") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    val days = pruneDays.toIntOrNull()
                                    if (days != null && days > 0) viewModel.pruneSessions(days)
                                },
                            ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val days = pruneDays.toIntOrNull()
                        if (days != null && days > 0) viewModel.pruneSessions(days)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.sessions_prune_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hidePruneDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Single-session delete confirmation dialog
    if (state.sessionToDeleteConfirm != null) {
        val sessionToDelete = state.sessionToDeleteConfirm
        val sessionTitle =
            state.sessions
                .find { it.id == sessionToDelete }
                ?.title
                ?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_untitled)
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteSession() },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                Text(stringResource(R.string.sessions_delete_message, sessionTitle))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteSession() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteSession() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Bulk delete confirmation dialog
    if (state.showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelBulkDelete() },
            title = { Text(stringResource(R.string.sessions_bulk_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sessions_bulk_delete_message,
                        state.selectedIds.size,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmBulkDelete() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBulkDelete() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_history)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadSessions() },
        modifier = modifier,
    ) {
        // Single scrolling column: search bar pinned on top, list fills below.
        // (The scaffold already applies top-bar padding via its inner Box, so we
        //  must NOT re-apply paddingValues here.)
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Search + bulk toggle (always visible) ─────────────
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    placeholder = stringResource(R.string.sessions_search_placeholder),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                IconButton(onClick = { viewModel.toggleSelecting() }) {
                    Icon(
                        imageVector = if (state.isSelecting) Icons.Filled.Close else Icons.Filled.SelectAll,
                        contentDescription =
                            if (state.isSelecting) {
                                stringResource(R.string.content_desc_exit_selection)
                            } else {
                                stringResource(R.string.content_desc_enter_selection)
                            },
                    )
                }
            }

            // List/state area takes all remaining height below the search bar.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isSearchMode -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            when {
                                state.isSearching && state.searchResults.isEmpty() -> {
                                    LoadingState()
                                }
                                state.searchError != null -> {
                                    ErrorState(
                                        message =
                                            state.searchError
                                                ?: stringResource(R.string.error_unknown),
                                        onRetry = { viewModel.setSearchQuery(state.searchQuery) },
                                    )
                                }
                                state.searchResults.isEmpty() -> {
                                    EmptyState(
                                        title = stringResource(R.string.sessions_search_empty_title),
                                        subtitle =
                                            stringResource(
                                                R.string.sessions_search_empty_desc,
                                                state.searchQuery,
                                            ),
                                        icon = Icons.Filled.Search,
                                    )
                                }
                                else -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text =
                                                stringResource(
                                                    R.string.sessions_search_results_header,
                                                    state.searchResults.size,
                                                    state.searchQuery,
                                                ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(
                                                        horizontal = spacing.md,
                                                        vertical = spacing.sm,
                                                    ),
                                        )
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listContentPadding,
                                            verticalArrangement = listItemSpacing,
                                        ) {
                                            items(sessionsToDisplay, key = { it.id }) { session ->
                                                SearchResultCard(
                                                    session = session,
                                                    query = state.searchQuery,
                                                    isSelecting = state.isSelecting,
                                                    isSelected = session.id in state.selectedIds,
                                                    isDeleting = session.id in state.deletingSessionIds,
                                                    highlightBackground = primaryContainer,
                                                    highlightForeground = onPrimaryContainer,
                                                    onCardClick = {
                                                        if (state.isSelecting) {
                                                            viewModel.toggleSessionSelection(session.id)
                                                        } else {
                                                            NavigationController.pendingSessionId = session.id
                                                            NavigationController.navigateTo(ChatScreen)
                                                        }
                                                    },
                                                    onCardLongClick = {
                                                        if (!state.isSelecting) {
                                                            viewModel.toggleSelecting()
                                                            viewModel.toggleSessionSelection(session.id)
                                                        }
                                                    },
                                                    onToggleSelection = {
                                                        viewModel.toggleSessionSelection(
                                                            session.id,
                                                        )
                                                    },
                                                    onDelete = { viewModel.requestDeleteSession(session.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    state.isLoading && state.sessions.isEmpty() -> {
                        LoadingState()
                    }

                    state.errorMessage != null -> {
                        val errorMsg = state.errorMessage
                        ErrorState(
                            message = errorMsg ?: stringResource(R.string.error_unknown),
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
                        Column(modifier = Modifier.fillMaxSize()) {
                            // ── Stats row ───────────────────────────────────────
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                StatCard(
                                    label = stringResource(R.string.sessions_stat_total),
                                    value = if (state.isLoadingStats) "…" else state.stats.total.toString(),
                                    icon = Icons.Filled.History,
                                    modifier = Modifier.weight(1f),
                                )
                                StatCard(
                                    label = stringResource(R.string.sessions_stat_active),
                                    value = if (state.isLoadingStats) "…" else state.stats.active.toString(),
                                    icon = Icons.Filled.CheckCircle,
                                    accentColor = statusColors.success,
                                    modifier = Modifier.weight(1f),
                                )
                                // Prune button card
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        ),
                                    onClick = { viewModel.showPruneDialog() },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(spacing.md),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteSweep,
                                                contentDescription = null,
                                                tint = statusColors.warning,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(modifier = Modifier.height(spacing.xs))
                                            Text(
                                                text = stringResource(R.string.sessions_action_prune),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = statusColors.warning,
                                            )
                                        }
                                    }
                                }
                            }
                            // Stats error snack
                            val statsError = state.statsError
                            if (statsError != null) {
                                Text(
                                    text = statsError,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColors.error,
                                    modifier = Modifier.padding(horizontal = spacing.md),
                                )
                            }

                            // ── Session list ────────────────────────────────────
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = listContentPadding,
                                verticalArrangement = listItemSpacing,
                            ) {
                                items(sessionsToDisplay, key = { it.id }) { session ->
                                    SessionCard(
                                        session = session,
                                        query = state.searchQuery,
                                        isSelecting = state.isSelecting,
                                        isSelected = session.id in state.selectedIds,
                                        isDeleting = session.id in state.deletingSessionIds,
                                        highlightBackground = primaryContainer,
                                        highlightForeground = onPrimaryContainer,
                                        onCardClick = {
                                            if (state.isSelecting) {
                                                viewModel.toggleSessionSelection(session.id)
                                            } else {
                                                NavigationController.pendingSessionId = session.id
                                                NavigationController.navigateTo(ChatScreen)
                                            }
                                        },
                                        onCardLongClick = {
                                            if (!state.isSelecting) {
                                                viewModel.toggleSelecting()
                                                viewModel.toggleSessionSelection(session.id)
                                            }
                                        },
                                        onToggleSelection = { viewModel.toggleSessionSelection(session.id) },
                                        onDelete = { viewModel.requestDeleteSession(session.id) },
                                    )
                                }

                                // Load more
                                if (state.hasMore || state.isLoadingMore) {
                                    item {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = spacing.sm),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (state.isLoadingMore) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Text(
                                                    text = stringResource(R.string.history_load_more),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier =
                                                        Modifier
                                                            .testTag("load_more_sessions")
                                                            .clickable(role = Role.Button) {
                                                                viewModel.loadMore()
                                                            },
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

        // ── Bulk action toolbar (animated) ──────────────────────────────────
        AnimatedVisibility(
            visible = state.isSelecting && hasSelection,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Select all / deselect
                        OutlinedButton(
                            onClick = {
                                if (state.selectedIds.size == state.sessions.size) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll()
                                }
                            },
                        ) {
                            Icon(
                                imageVector =
                                    if (state.selectedIds.size == state.sessions.size) {
                                        Icons.Filled.Close
                                    } else {
                                        Icons.Filled.SelectAll
                                    },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(
                                if (state.selectedIds.size == state.sessions.size) {
                                    stringResource(R.string.sessions_action_deselect_all)
                                } else {
                                    stringResource(R.string.sessions_action_select_all)
                                },
                            )
                        }

                        // Delete selected
                        Button(
                            onClick = { viewModel.requestBulkDelete() },
                            enabled = !state.isDeletingBulk,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = statusColors.error,
                                ),
                        ) {
                            if (state.isDeletingBulk) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(
                                stringResource(
                                    R.string.sessions_action_delete_n,
                                    state.selectedIds.size,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: com.m57.hermescontrol.data.model.SessionInfo,
    query: String,
    isSelecting: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    highlightBackground: Color,
    highlightForeground: Color,
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val displayTitle = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_untitled)
    val isActive = session.status?.lowercase() == "active" || session.status?.lowercase() == "streaming"
    val srcIcon = sourceIcon(session.source)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("session_card_${session.id}")
                .combinedClickable(
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border =
            if (isActive && !isSelecting) {
                BorderStroke(2.dp, statusColors.success)
            } else if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox in select mode
            if (isSelecting) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.testTag("session_checkbox_${session.id}"),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }

            // Source icon
            if (srcIcon != null && !isSelecting) {
                Icon(
                    imageVector = srcIcon,
                    contentDescription = sourceLabel(session.source),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }

            // Main content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (query.isNotBlank()) {
                            highlightText(displayTitle, query, highlightBackground, highlightForeground)
                        } else {
                            AnnotatedString(displayTitle)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(spacing.xs))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.history_message_count, session.message_count ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!session.status.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(spacing.sm))
                        StatusBadge(
                            text = session.status,
                            status = if (isActive) StatusBadgeType.SUCCESS else StatusBadgeType.NEUTRAL,
                        )
                    }
                }
            }

            // Action buttons (not in select mode)
            if (!isSelecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    // Delete
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                modifier = Modifier.size(16.dp),
                                tint = statusColors.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search-result card. The backend search payload has no session title, so this card is
 * honest about it: it shows a "Match" label + the highlighted snippet as the body, plus
 * source / model / played-at metadata chips. It never presents the snippet as a name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultCard(
    session: com.m57.hermescontrol.data.model.SessionInfo,
    query: String,
    isSelecting: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    highlightBackground: Color,
    highlightForeground: Color,
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val snippet = session.preview?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_untitled)
    val playedAt = formatPlayedAt(session.started_at)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("session_card_${session.id}")
                .combinedClickable(
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border =
            if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            // Checkbox in select mode
            if (isSelecting) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.testTag("session_checkbox_${session.id}"),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }

            Column(modifier = Modifier.weight(1f)) {
                // Header row: "Match" label + source icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    val srcIcon = sourceIcon(session.source)
                    if (srcIcon != null && !isSelecting) {
                        Icon(
                            imageVector = srcIcon,
                            contentDescription = sourceLabel(session.source),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.sessions_search_match_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                    )
                    if (!session.id.isNullOrBlank()) {
                        Text(
                            text = "· ${session.id.take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(spacing.xs))

                // The matched snippet, highlighted — shown as the body, NOT as a title.
                Text(
                    text = highlightText(snippet, query, highlightBackground, highlightForeground),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(spacing.sm))

                // Metadata chips row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    session.source?.let { src ->
                        StatusBadge(
                            text = sourceLabel(src),
                            status = StatusBadgeType.NEUTRAL,
                        )
                    }
                    session.model?.let { mdl ->
                        StatusBadge(
                            text = stringResource(R.string.sessions_search_model_label) + ": " + mdl,
                            status = StatusBadgeType.INFO,
                        )
                    }
                    playedAt?.let { time ->
                        StatusBadge(
                            text = stringResource(R.string.sessions_search_played_at, time),
                            status = StatusBadgeType.NEUTRAL,
                        )
                    }
                }

                // Tap hint when not selecting
                if (!isSelecting) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = stringResource(R.string.sessions_search_open_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Action buttons (not in select mode)
            if (!isSelecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                modifier = Modifier.size(16.dp),
                                tint = statusColors.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
