package com.m57.hermescontrol.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar

@Composable
fun AchievementsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: AchievementsViewModel = viewModel { AchievementsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filteredAchievements =
        remember(query, state.achievements) {
            state.achievements.filter { achievement ->
                achievement.name.contains(query, ignoreCase = true) ||
                    achievement.description?.contains(query, ignoreCase = true) == true ||
                    achievement.category?.contains(query, ignoreCase = true) == true
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadAchievements()
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_achievements)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAchievements() },
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search achievements...",
                        )
                    }
                    items(filteredAchievements, key = { it.id }) { achievement ->
                        val pct = achievement.progress_pct?.toFloat()?.div(100f) ?: 0f

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (achievement.unlocked) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
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
                                            )
                                        }
                                    }

                                    // Display Icon/Emoji/Tier
                                    val badgeText =
                                        when {
                                            achievement.unlocked -> achievement.tier ?: "🏆"
                                            achievement.discovered -> "🔒 Discovered"
                                            else -> "❓ Secret"
                                        }
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color =
                                            if (achievement.unlocked) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }

                                achievement.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }

                                if (achievement.discovered && !achievement.unlocked) {
                                    Spacer(modifier = Modifier.height(4.dp))
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
                                                    achievement.next_threshold?.toInt() ?: 0,
                                                ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            text = "${achievement.progress_pct?.toInt() ?: 0}%",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { pct.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
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
