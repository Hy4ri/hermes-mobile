package com.m57.hermescontrol.ui.plugins.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.plugins.PluginsUiState

@Composable
fun CatalogPluginsView(
    state: PluginsUiState,
    filteredEntries: List<PluginCatalogEntry>,
    onQueryChange: (String) -> Unit,
    onTierFilterChange: (String?) -> Unit,
    onInstall: (PluginCatalogEntry) -> Unit,
    onUpdate: (PluginCatalogEntry) -> Unit,
    onShowDetail: (PluginCatalogEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = state.catalogQuery,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.plugins_catalog_search_placeholder),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        FilterChipRow(
            chips = listOf(null, "official", "community"),
            selectedChip = state.catalogTierFilter,
            onChipSelected = onTierFilterChange,
            modifier = Modifier.padding(vertical = 2.dp),
            chipLabel = { tier ->
                Text(
                    text =
                        when (tier) {
                            null -> stringResource(R.string.plugins_catalog_filter_all)
                            "official" -> stringResource(R.string.plugins_catalog_filter_official)
                            "community" -> stringResource(R.string.plugins_catalog_filter_community)
                            else -> tier
                        },
                )
            },
        )

        when {
            state.isCatalogLoading && state.catalogEntries.isEmpty() -> {
                SkeletonListState()
            }

            state.catalogErrorMessage != null && state.catalogEntries.isEmpty() -> {
                ErrorState(
                    message = state.catalogErrorMessage,
                    onRetry = onRetry,
                )
            }

            filteredEntries.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.plugins_catalog_empty_title),
                    subtitle = stringResource(R.string.plugins_catalog_empty_desc),
                    onAction = onRetry,
                    actionLabel = stringResource(R.string.content_desc_refresh),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(filteredEntries, key = { it.name }) { entry ->
                        CatalogPluginCard(
                            entry = entry,
                            isInstalling = state.catalogInstallingName == entry.name,
                            onInstall = { onInstall(entry) },
                            onUpdate = { onUpdate(entry) },
                            onClick = { onShowDetail(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CatalogPluginCard(
    entry: PluginCatalogEntry,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: Title + Maintainer on left, Tier badge on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!entry.maintainer.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.plugins_catalog_maintainer, entry.maintainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val isOfficial = entry.isOfficial
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                        if (isOfficial) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                ) {
                    Text(
                        text =
                            if (isOfficial) {
                                stringResource(R.string.plugins_catalog_tier_official)
                            } else {
                                stringResource(R.string.plugins_catalog_tier_community)
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isOfficial) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Description
            entry.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom row: Status badge on left, Action button on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry.installed) {
                        Text(
                            text = stringResource(R.string.plugins_catalog_installed_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColors.success,
                        )
                    }
                    if (entry.updateAvailable) {
                        Text(
                            text = stringResource(R.string.plugins_catalog_update),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColors.warning,
                        )
                    }
                }

                if (entry.installed && entry.updateAvailable) {
                    OutlinedButton(
                        onClick = onUpdate,
                        enabled = !isInstalling,
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.plugins_catalog_update))
                    }
                } else if (entry.installed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = statusColors.success,
                        )
                        Text(
                            text = stringResource(R.string.plugins_catalog_installed_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.success,
                        )
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !isInstalling,
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.InstallDesktop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.plugins_action_install))
                    }
                }
            }
        }
    }
}
