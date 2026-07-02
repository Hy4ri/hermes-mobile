package com.m57.hermescontrol.ui.achievements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Achievement
import com.m57.hermescontrol.data.model.RecentUnlock
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect

@Composable
fun AchievementsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: AchievementsViewModel = viewModel { AchievementsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val filteredAchievements =
        remember(query, state.activeCategory, state.activeState, state.achievements) {
            state.achievements.filter { a ->
                val matchesSearch =
                    query.isEmpty() ||
                        a.name.contains(query, ignoreCase = true) ||
                        a.description?.contains(query, ignoreCase = true) == true ||
                        a.category?.contains(query, ignoreCase = true) == true
                val matchesCategory =
                    state.activeCategory == null || a.category == state.activeCategory
                val matchesState =
                    state.activeState == null || a.state == state.activeState
                matchesSearch && matchesCategory && matchesState
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadAchievements()
        viewModel.loadRecentUnlocks()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.achievements_reset_title)) },
            text = { Text(stringResource(R.string.achievements_reset_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetState()
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_achievements)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAchievements() },
        modifier = modifier,
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.achievements_menu_desc),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.achievements_action_rescan)) },
                        onClick = {
                            showMenu = false
                            viewModel.rescan()
                        },
                        enabled = !state.isRescanning,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.achievements_action_reset),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            showResetDialog = true
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.achievements.isEmpty() -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.achievements.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadAchievements() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.achievements.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.achievements_empty_title),
                    subtitle = stringResource(R.string.achievements_empty_desc),
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
<<<<<<< feat/447-webhooks-full-parity
                    modifier = Modifier.fillMaxSize(),
=======
                    modifier =
                        Modifier
                            .fillMaxSize(),
>>>>>>> main
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Search
                    item(key = "search") {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.achievements_search_hint),
                        )
                    }

                    // Header stats
                    item(key = "stats") {
                        StatsRow(
                            unlockedCount = state.unlockedCount,
                            discoveredCount = state.discoveredCount,
                            secretCount = state.secretCount,
                            totalCount = state.totalCount,
                        )
                    }

                    // Scan status
                    item(key = "scan_status") {
                        ScanStatusChip(
                            scanState = state.scanState,
                            isStale = state.isStale,
                            scanLastError = state.scanLastError,
                            scanRunCount = state.scanRunCount,
                            isRescanning = state.isRescanning,
                        )
                    }

                    // Category filters
                    if (state.categories.isNotEmpty()) {
                        item(key = "category_filters") {
                            CategoryFilterRow(
                                categories = state.categories,
                                activeCategory = state.activeCategory,
                                onCategorySelected = { selected ->
                                    viewModel.setActiveCategory(
                                        if (selected == state.activeCategory) null else selected,
                                    )
                                },
                            )
                        }
                    }

                    // State filters
                    item(key = "state_filters") {
                        StateFilterRow(
                            activeState = state.activeState,
                            onStateSelected = { selected ->
                                viewModel.setActiveState(
                                    if (selected == state.activeState) null else selected,
                                )
                            },
                        )
                    }

                    // Recent unlocks
                    if (state.recentUnlocks.isNotEmpty()) {
                        item(key = "recent_header") {
                            SectionHeader(
                                title = stringResource(R.string.achievements_recent_title),
                            )
                        }
                        items(
                            items = state.recentUnlocks.take(3),
                            key = { "recent_${it.id}" },
                        ) { unlock ->
                            RecentUnlockCard(unlock = unlock)
                        }
                    }

                    // Achievement count label
                    item(key = "count_label") {
                        Text(
                            text =
                                stringResource(
                                    R.string.achievements_count_label,
                                    filteredAchievements.size,
                                    state.totalCount,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // Achievement cards
                    items(filteredAchievements, key = { it.id }) { achievement ->
                        AchievementCard(
                            achievement = achievement,
                        )
                    }
                }
            }
        }
    }
}

// ── Stats Row ──────────────────────────────────────────────────────────

@Composable
private fun StatsRow(
    unlockedCount: Int,
    discoveredCount: Int,
    secretCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatPill(
            label = stringResource(R.string.achievements_stat_unlocked),
            value = "$unlockedCount",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        StatPill(
            label = stringResource(R.string.achievements_stat_discovered),
            value = "$discoveredCount",
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        StatPill(
            label = stringResource(R.string.achievements_stat_secret),
            value = "$secretCount",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        StatPill(
            label = stringResource(R.string.achievements_stat_total),
            value = "$totalCount",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f),
            )
        }
    }
}

// ── Scan Status Chip ───────────────────────────────────────────────────

@Composable
private fun ScanStatusChip(
    scanState: String,
    isStale: Boolean,
    scanLastError: String?,
    scanRunCount: Int,
    isRescanning: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (label, type) =
            when {
                isRescanning -> stringResource(R.string.achievements_scan_scanning) to StatusBadgeType.INFO
                scanState == "running" -> stringResource(R.string.achievements_scan_running) to StatusBadgeType.WARNING
                scanLastError != null -> stringResource(R.string.achievements_scan_error) to StatusBadgeType.ERROR
                else -> stringResource(R.string.achievements_scan_idle) to StatusBadgeType.SUCCESS
            }
        StatusBadge(text = label, status = type)

        if (isStale) {
            StatusBadge(
                text = stringResource(R.string.achievements_stale),
                status = StatusBadgeType.WARNING,
            )
        }

        if (scanRunCount > 0) {
            Text(
                text = stringResource(R.string.achievements_scan_count, scanRunCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Category Filter Row ────────────────────────────────────────────────

@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    activeCategory: String?,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all_cat") {
            FilterChip(
                selected = activeCategory == null,
                onClick = { onCategorySelected("") },
                label = { Text(stringResource(R.string.achievements_filter_all)) },
            )
        }
        items(categories, key = { it }) { category ->
            FilterChip(
                selected = category == activeCategory,
                onClick = { onCategorySelected(category) },
                label = { Text(category) },
            )
        }
    }
}

// ── State Filter Row ───────────────────────────────────────────────────

@Composable
private fun StateFilterRow(
    activeState: String?,
    onStateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters =
        listOf(
            stringResource(R.string.achievements_filter_all) to null,
            stringResource(R.string.achievements_state_unlocked) to "unlocked",
            stringResource(R.string.achievements_state_discovered) to "discovered",
            stringResource(R.string.achievements_state_secret) to "secret",
        )
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.second ?: "all" }) { (label, stateValue) ->
            FilterChip(
                selected = activeState == stateValue,
                onClick = { onStateSelected(stateValue ?: "") },
                label = { Text(label) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor =
                            when (stateValue) {
                                "unlocked" -> MaterialTheme.colorScheme.primaryContainer
                                "discovered" -> MaterialTheme.colorScheme.tertiaryContainer
                                "secret" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                    ),
            )
        }
    }
}

// ── Recent Unlock Card ─────────────────────────────────────────────────

@Composable
private fun RecentUnlockCard(
    unlock: RecentUnlock,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = AchievementIcon.emoji(unlock.icon), style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = unlock.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                unlock.tier?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            unlock.unlockedAt?.let {
                Text(
                    text = formatTimestamp(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Achievement Card ───────────────────────────────────────────────────

@Composable
private fun AchievementCard(
    achievement: Achievement,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val pct = achievement.progressPct?.toFloat()?.div(100f) ?: 0f
    val isUnlocked = achievement.unlocked
    val isDiscovered = achievement.discovered && !isUnlocked

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        isUnlocked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        isDiscovered -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        border =
            if (isUnlocked) {
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                )
            } else {
                null
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: icon + name + tier badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Icon emoji
                    Text(
                        text = AchievementIcon.emoji(achievement.icon),
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = achievement.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        achievement.category?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Tier / state badge
                val stateText =
                    when {
                        isUnlocked -> achievement.tier ?: "\uD83C\uDFC6"
                        achievement.state == "secret" -> "\u2753"
                        isDiscovered -> "\uD83D\uDD13"
                        else -> "\u2753"
                    }
                val stateColor =
                    when {
                        isUnlocked -> MaterialTheme.colorScheme.primary
                        achievement.state == "secret" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor,
                )
            }

            // Description
            achievement.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar (discovered but not unlocked)
            if (isDiscovered) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.achievements_label_progress,
                                achievement.progress?.toInt() ?: 0,
                                achievement.nextThreshold?.toInt() ?: 0,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${achievement.progressPct?.toInt() ?: 0}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LinearProgressIndicator(
                    progress = { pct.coerceIn(0f, 1f) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            // Unlocked progress bar and unlocked_at
            if (isUnlocked) {
                achievement.unlockedAt?.let {
                    Text(
                        text = stringResource(R.string.achievements_unlocked_at, formatTimestamp(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Expandable criteria
            achievement.criteria?.let { criteria ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text =
                            if (expanded) {
                                stringResource(
                                    R.string.achievements_criteria_hide,
                                )
                            } else {
                                stringResource(R.string.achievements_criteria_show)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = criteria,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Surface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        content = content,
    )
}

// ── Helpers ────────────────────────────────────────────────────────────

private fun formatTimestamp(epochSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochSeconds * 1000))
}
