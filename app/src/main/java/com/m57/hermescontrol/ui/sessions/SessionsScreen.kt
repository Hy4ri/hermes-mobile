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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import com.m57.hermescontrol.data.model.SessionTreeItem
import com.m57.hermescontrol.data.model.flattenSessionsWithBranches
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.sessions.components.BranchRow
import com.m57.hermescontrol.ui.sessions.components.PinnedSectionHeader
import com.m57.hermescontrol.ui.sessions.components.SearchResultCard
import com.m57.hermescontrol.ui.sessions.components.SessionCard
import com.m57.hermescontrol.ui.sessions.components.SessionsBulkActionBar
import com.m57.hermescontrol.ui.sessions.components.SessionsDialogs
import com.m57.hermescontrol.ui.sessions.components.SessionsStatsRow
import com.m57.hermescontrol.ui.sessions.components.automationGroups

/**
 * Auto-load the next history page when the user scrolls to within this many
 * items of the end — a pre-load buffer so paging feels continuous.
 */
private const val AUTO_LOAD_THRESHOLD = 6

/**
 * Maps a session source string to a Material icon for visual identification.
 */
private fun sourceIcon(source: String?): ImageVector? =
    when (source?.lowercase()) {
        "telegram", "tg" -> Icons.AutoMirrored.Filled.Send
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

internal fun displayedSessions(state: SessionsUiState): List<SessionTreeItem> =
    if (state.isSearchMode) {
        state.searchResults.map { result ->
            val session = result.toSessionInfo()
            SessionTreeItem(
                session = session,
                depth = 0,
                branchStem = null,
                displayTitle =
                    session.title?.takeIf(String::isNotBlank)
                        ?: session.display_name?.takeIf(String::isNotBlank)
                        ?: session.preview?.takeIf(String::isNotBlank)?.take(80)
                        ?: "Untitled",
            )
        }
    } else {
        flattenSessionsWithBranches(state.displaySessions)
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
    var expandedAutomationGroups by remember { mutableStateOf(emptySet<String>()) }

    val sessionsToDisplay =
        remember(
            state.isSearchMode,
            state.searchQuery,
            state.sessions,
            state.showHidden,
            state.searchResults,
        ) {
            displayedSessions(state)
        }

    val pinnedItems =
        remember(
            state.isSearchMode,
            state.pinnedSessions,
            state.showHidden,
        ) {
            if (state.isSearchMode) {
                emptyList()
            } else {
                flattenSessionsWithBranches(state.pinnedSessions, preserveOrder = true)
            }
        }

    val hasSelection = state.selectedIds.isNotEmpty()
    val loadedMessageCount = state.sessions.sumOf { it.message_count ?: 0 }
    val automationSessionGroups = remember(sessionsToDisplay) { automationGroups(sessionsToDisplay.map { it.session }) }
    val sessionItemsById = remember(sessionsToDisplay) { sessionsToDisplay.associateBy { it.session.id } }
    val visibleSessionIds =
        remember(sessionsToDisplay) {
            sessionsToDisplay.mapTo(linkedSetOf()) { it.session.id }
        }
    val allVisibleSessionsSelected =
        visibleSessionIds.isNotEmpty() && visibleSessionIds.all { it in state.selectedIds }
    val listPadding =
        PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = if (hasSelection) 72.dp else 8.dp,
        )

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    DisposableEffect(Unit) {
        viewModel.startLiveStatusTracking()
        onDispose {
            viewModel.stopLiveStatusTracking()
        }
    }

    // Toast effect
    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = { viewModel.clearToast() },
    )

    // Empty-session cleanup dialog (issue #787)
    if (state.showEmptyCleanupDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideEmptyCleanupDialog() },
            title = { Text(stringResource(R.string.sessions_empty_cleanup_title)) },
            text = { Text(stringResource(R.string.sessions_empty_cleanup_message, state.emptyCount)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmEmptyCleanup() }) {
                    if (state.isCleaningEmpty) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.sessions_empty_cleanup_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideEmptyCleanupDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
                ?.takeIf { it.isNotBlank() }
                ?: state.searchResults
                    .find { it.session_id == sessionToDelete }
                    ?.snippet
                    ?.let(::cleanSearchSnippet)
                    ?.take(80)
                ?: stringResource(R.string.history_untitled)
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

    // Single-session rename dialog (issue #785) — opened from the card's
    // hold menu; PATCH /api/sessions/{id} with {title}.
    state.renamingSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { viewModel.closeRenameDialog() },
            title = { Text(stringResource(R.string.sessions_rename_title)) },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = { viewModel.updateRenameDraft(it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sessions_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.renameSession(sessionId, state.renameDraft) },
                    enabled = state.renameDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.sessions_action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeRenameDialog() }) {
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
        actions = {
            IconButton(
                onClick = { NavigationController.openNewChat() },
                modifier = Modifier.testTag("sessions_action_new_chat"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.content_desc_new_chat),
                )
            }
            if (state.hasHiddenSessions) {
                IconButton(
                    onClick = { viewModel.toggleShowHidden() },
                    modifier = Modifier.testTag("sessions_action_toggle_hidden"),
                ) {
                    Icon(
                        imageVector =
                            if (state.showHidden) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                        contentDescription =
                            if (state.showHidden) {
                                stringResource(R.string.sessions_hide_hidden)
                            } else {
                                stringResource(R.string.sessions_show_hidden)
                            },
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        // Single scrolling column: search bar pinned on top, list fills below.
        // (The scaffold already applies top-bar padding via its inner Box, so we
        //  must NOT re-apply paddingValues here.)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FilterChip(
                    selected = state.section == HistorySection.CONVERSATIONS,
                    onClick = { viewModel.selectSection(HistorySection.CONVERSATIONS) },
                    label = { Text(stringResource(R.string.sessions_tab_conversations)) },
                    modifier = Modifier.testTag("history_tab_conversations"),
                )
                FilterChip(
                    selected = state.section == HistorySection.AUTOMATIONS,
                    onClick = { viewModel.selectSection(HistorySection.AUTOMATIONS) },
                    label = { Text(stringResource(R.string.sessions_tab_automations)) },
                    modifier = Modifier.testTag("history_tab_automations"),
                )
            }

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
                // This API operates on all history, so do not present it as scoped
                // to the Automations tab.
                if (state.section == HistorySection.CONVERSATIONS) {
                    BadgedBox(
                        badge = {
                            if (state.emptyCount > 0) {
                                Badge { Text("${state.emptyCount}") }
                            }
                        },
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.requestEmptyCleanup() },
                            enabled = state.emptyCount > 0,
                            contentPadding = PaddingValues(horizontal = spacing.md, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(stringResource(R.string.sessions_empty_cleanup_desc))
                        }
                    }
                }
            }

            // List/state area takes all remaining height below the search bar.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isSearchMode -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            when {
                                state.isSearching && state.searchResults.isEmpty() -> {
                                    SkeletonListState()
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
                                            contentPadding = listPadding,
                                            verticalArrangement = listItemSpacing,
                                        ) {
                                            items(sessionsToDisplay, key = { it.session.id }) { item ->
                                                val session = item.session
                                                SearchResultCard(
                                                    session = session,
                                                    query = state.searchQuery,
                                                    isSelecting = state.isSelecting,
                                                    isSelected = session.id in state.selectedIds,
                                                    isDeleting = session.id in state.deletingSessionIds,
                                                    liveStatus = state.liveStatuses[session.id],
                                                    highlightBackground = primaryContainer,
                                                    highlightForeground = onPrimaryContainer,
                                                    onCardClick = {
                                                        if (state.isSelecting) {
                                                            viewModel.toggleSessionSelection(session.id)
                                                        } else {
                                                            NavigationController.openChatSession(session.id)
                                                        }
                                                    },
                                                    onToggleSelection = {
                                                        viewModel.toggleSessionSelection(
                                                            session.id,
                                                        )
                                                    },
                                                    onSelect = {
                                                        viewModel.toggleSelecting()
                                                        viewModel.toggleSessionSelection(session.id)
                                                    },
                                                    onRename = {
                                                        viewModel.openRenameDialog(
                                                            session.id,
                                                            session.title.orEmpty(),
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
                        SkeletonListState()
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
                            title =
                                stringResource(
                                    if (state.section == HistorySection.AUTOMATIONS) {
                                        R.string.sessions_automations_empty_title
                                    } else {
                                        R.string.history_empty_title
                                    },
                                ),
                            subtitle =
                                stringResource(
                                    if (state.section == HistorySection.AUTOMATIONS) {
                                        R.string.sessions_automations_empty_desc
                                    } else {
                                        R.string.history_empty_desc
                                    },
                                ),
                            icon = Icons.Filled.History,
                            actionLabel =
                                if (state.section == HistorySection.CONVERSATIONS) {
                                    stringResource(R.string.empty_action_start_chat)
                                } else {
                                    null
                                },
                            onAction = { NavigationController.navigateTo(ChatScreen) },
                        )
                    }

                    state.displaySessions.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.history_empty_title),
                            subtitle = stringResource(R.string.sessions_all_hidden_desc),
                            icon = Icons.Filled.VisibilityOff,
                            actionLabel = stringResource(R.string.sessions_show_hidden),
                            onAction = { viewModel.toggleShowHidden() },
                        )
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // ── Stats row ───────────────────────────────────────
                            SessionsStatsRow(
                                total = state.total,
                                loadedMessageCount = loadedMessageCount,
                                section = state.section,
                                spacing = spacing,
                                statusColors = statusColors,
                                onPruneClick = { viewModel.showPruneDialog() },
                            )

                            // ── Session list ────────────────────────────────────
                            val listState = rememberLazyListState()
                            // Fluid infinite scroll: once the user reaches within
                            // AUTO_LOAD_THRESHOLD items of the end, pull the next
                            // page automatically. Driven by real scroll position
                            // (layoutInfo is snapshot state), so it recomputes on
                            // every scroll — not a frozen first-composition read.
                            val nearListEnd by remember {
                                derivedStateOf {
                                    val info = listState.layoutInfo
                                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                                    if (lastVisible < 0 || info.totalItemsCount == 0) {
                                        false
                                    } else if (info.totalItemsCount <= AUTO_LOAD_THRESHOLD) {
                                        lastVisible >= info.totalItemsCount - 1
                                    } else {
                                        lastVisible >= info.totalItemsCount - AUTO_LOAD_THRESHOLD
                                    }
                                }
                            }
                            LaunchedEffect(
                                nearListEnd,
                                state.isLoadingMore,
                                state.hasMore,
                                state.isSearchMode,
                            ) {
                                if (
                                    state.section == HistorySection.CONVERSATIONS &&
                                    nearListEnd &&
                                    !state.isLoadingMore &&
                                    state.hasMore &&
                                    !state.isSearchMode
                                ) {
                                    viewModel.loadMore()
                                }
                            }
                            val sessionCard: @Composable (SessionTreeItem) -> Unit = { item ->
                                val session = item.session
                                BranchRow(item = item) {
                                    SessionCard(
                                        session = session,
                                        displayTitle = item.displayTitle,
                                        branchStem = null,
                                        isFork = item.isFork,
                                        forkDepth = item.forkDepth,
                                        query = state.searchQuery,
                                        isSelecting = state.isSelecting,
                                        isSelected = session.id in state.selectedIds,
                                        isDeleting = session.id in state.deletingSessionIds,
                                        isPinned = session.pinned == true,
                                        isHidden = session.hidden == true,
                                        liveStatus = state.liveStatuses[session.id],
                                        highlightBackground = primaryContainer,
                                        highlightForeground = onPrimaryContainer,
                                        onCardClick = {
                                            if (state.isSelecting) {
                                                viewModel.toggleSessionSelection(session.id)
                                            } else {
                                                NavigationController.openChatSession(session.id)
                                            }
                                        },
                                        onToggleSelection = { viewModel.toggleSessionSelection(session.id) },
                                        onSelect = {
                                            viewModel.toggleSelecting()
                                            viewModel.toggleSessionSelection(session.id)
                                        },
                                        onRename = {
                                            viewModel.openRenameDialog(
                                                session.id,
                                                item.displayTitle,
                                            )
                                        },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onToggleHide = { viewModel.toggleHide(session.id) },
                                        onDelete = { viewModel.requestDeleteSession(session.id) },
                                    )
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = listPadding,
                                verticalArrangement = listItemSpacing,
                            ) {
                                if (state.section == HistorySection.AUTOMATIONS) {
                                    automationGroups(
                                        groups = automationSessionGroups,
                                        expandedGroups = expandedAutomationGroups,
                                        sessionItemsById = sessionItemsById,
                                        spacing = spacing,
                                        onToggleGroup = { groupKey ->
                                            expandedAutomationGroups =
                                                if (groupKey in expandedAutomationGroups) {
                                                    expandedAutomationGroups - groupKey
                                                } else {
                                                    expandedAutomationGroups + groupKey
                                                }
                                        },
                                        renderSessionCard = sessionCard,
                                    )
                                } else {
                                    if (pinnedItems.isNotEmpty() && !state.isSearchMode) {
                                        item(key = "pinned_header") {
                                            PinnedSectionHeader(
                                                count = pinnedItems.size,
                                                expanded = state.pinnedExpanded,
                                                onToggle = { viewModel.togglePinnedExpanded() },
                                            )
                                        }
                                        if (state.pinnedExpanded) {
                                            items(pinnedItems, key = { "pin_${it.session.id}" }) { item ->
                                                sessionCard(item)
                                            }
                                        }
                                        item(key = "history_header") {
                                            Text(
                                                text = "History",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = spacing.xs),
                                            )
                                        }
                                    }
                                    items(sessionsToDisplay, key = { it.session.id }) { item ->
                                        sessionCard(item)
                                    }
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
        SessionsBulkActionBar(
            visible = state.isSelecting && hasSelection,
            allVisibleSessionsSelected = allVisibleSessionsSelected,
            selectedCount = state.selectedIds.size,
            isDeletingBulk = state.isDeletingBulk,
            spacing = spacing,
            statusColors = statusColors,
            onSelectAllToggle = {
                if (allVisibleSessionsSelected) {
                    viewModel.clearSelection()
                } else {
                    viewModel.selectAll(visibleSessionIds)
                }
            },
            onDeleteSelected = { viewModel.requestBulkDelete() },
        )
    }
}
