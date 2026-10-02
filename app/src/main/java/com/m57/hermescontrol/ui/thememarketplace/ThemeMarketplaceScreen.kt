package com.m57.hermescontrol.ui.thememarketplace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.theme.import.ThemeApplier
import com.m57.hermescontrol.data.theme.marketplace.MarketplaceThemeEntry
import com.m57.hermescontrol.data.theme.marketplace.ThemeAssets
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

/**
 * Marketplace browser: live search over the public VS Code Gallery.
 *
 * Provenance is stated in the header copy (spec M1) — this is the same
 * ExtensionQuery endpoint the desktop browses directly, not a Hermes-hosted
 * catalog and not a local preset. Tapping a row *selects* it and opens a
 * detail dialog; only the explicit Apply control inside that dialog changes the
 * theme (spec M4). The applied theme is badged in the list and named in the
 * dialog (spec M5).
 */
@Composable
fun ThemeMarketplaceScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ThemeMarketplaceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeCustomThemeId by viewModel.activeCustomThemeId.collectAsStateWithLifecycle()
    val activeCustomThemeName by viewModel.activeCustomThemeName.collectAsStateWithLifecycle()
    val themePreset by AuthManager.themePresetFlow.collectAsStateWithLifecycle()
    val restoreFailed by ThemeApplier.restoreFailed.collectAsStateWithLifecycle()
    val unavailableThemeName by ThemeApplier.unavailableThemeName.collectAsStateWithLifecycle()
    val unavailableThemeId by ThemeApplier.unavailableThemeId.collectAsStateWithLifecycle()

    // CUSTOM + a recorded restore failure means the import did not survive and
    // Theme.kt is falling back to Default, so say that instead of badging an
    // active Marketplace theme (spec M6).
    val customThemeUnavailable = themePreset == ThemePreset.CUSTOM && restoreFailed
    val activeId = activeCustomThemeId
    val activeName = activeCustomThemeName

    HermesScaffold(
        modifier = modifier,
        title = { Text(text = stringResource(R.string.screen_themes)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        drawerGesturesEnabled = true,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.theme_marketplace_provenance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Name the applied theme and its gallery id, so a restored custom
            // theme is never mistaken for a built-in preset (spec M5).
            if (activeId != null) {
                Text(
                    text = stringResource(R.string.theme_marketplace_active_source, activeName ?: activeId, activeId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SearchBar(
                query = uiState.query,
                onQueryChange = viewModel::setQuery,
                placeholder = stringResource(R.string.theme_marketplace_search_placeholder),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (customThemeUnavailable) {
                ThemeMarketplaceRecoveryBanner(
                    themeName = unavailableThemeName,
                    onReapply = {
                        // The catalog is right here: search for the id we failed
                        // to restore so the user can apply it again.
                        unavailableThemeId?.let(viewModel::setQuery)
                    },
                    onClear = { ThemeApplier.clearCustomTheme() },
                )
            }

            if (uiState.isLoading && uiState.entries.isEmpty()) {
                SkeletonListState()
            } else if (uiState.errorMessage != null && uiState.entries.isEmpty()) {
                ErrorState(
                    message = uiState.errorMessage!!,
                    onRetry = viewModel::retry,
                )
            } else if (uiState.entries.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.theme_marketplace_empty_title),
                    subtitle = stringResource(R.string.theme_marketplace_empty_desc),
                    onAction = viewModel::retry,
                    actionLabel = stringResource(R.string.content_desc_refresh),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(uiState.entries, key = { it.extensionId }) { entry ->
                        ThemeMarketplaceCard(
                            entry = entry,
                            onSelect = { viewModel.selectEntry(entry) },
                            isActive = activeId == entry.extensionId,
                            isApplying = entry.extensionId == uiState.applyingExtensionId,
                            applyError =
                                uiState.applyError
                                    .takeIf { uiState.applyErrorExtensionId == entry.extensionId },
                        )
                    }
                    if (uiState.canLoadMore) {
                        item(key = "load_more") {
                            LoadMoreRow(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    val selected = uiState.entries.firstOrNull { it.extensionId == uiState.selectedEntry }
    if (selected != null) {
        val isActive = activeId == selected.extensionId
        val isApplying = uiState.applyingExtensionId == selected.extensionId
        val selectedAssets = uiState.resolvedAssets[selected.extensionId]
        DetailDialog(
            title = selected.displayName,
            rows = buildDetailRows(selected, selectedAssets),
            onDismiss = { viewModel.selectEntry(null) },
            actions = {
                selectedAssets?.previewUrl?.let { previewUrl ->
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Button(
                    onClick = { viewModel.applyTheme(selected) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isApplying && !isActive,
                ) {
                    Text(
                        text =
                            when {
                                isActive -> stringResource(R.string.theme_marketplace_already_active)
                                isApplying -> stringResource(R.string.theme_marketplace_applying)
                                else -> stringResource(R.string.theme_marketplace_apply)
                            },
                    )
                }
                uiState.applyError
                    ?.takeIf { uiState.applyErrorExtensionId == selected.extensionId }
                    ?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            },
        )
    }
}

@Composable
fun ThemeMarketplaceCard(
    entry: MarketplaceThemeEntry,
    onSelect: () -> Unit,
    isActive: Boolean,
    isApplying: Boolean,
    applyError: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onSelect() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.theme_marketplace_subtitle, entry.publisher, entry.installs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.description.isNotBlank()) {
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isApplying) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.theme_marketplace_applying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (applyError != null) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = applyError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (isActive) {
                StatusBadge(
                    text = stringResource(R.string.theme_marketplace_active),
                    status = StatusBadgeType.SUCCESS,
                )
            }
        }
    }
}

@Composable
fun LoadMoreRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.theme_marketplace_load_more),
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun buildDetailRows(
    entry: MarketplaceThemeEntry,
    assets: ThemeAssets?,
): List<DetailRow> =
    listOfNotNull(
        DetailRow(
            label = stringResource(R.string.theme_marketplace_label_publisher),
            value = entry.publisher,
        ),
        DetailRow(
            label = stringResource(R.string.theme_marketplace_label_installs),
            value = entry.installs.toString(),
        ),
        DetailRow(
            label = stringResource(R.string.theme_marketplace_label_id),
            value = entry.extensionId,
        ),
        DetailRow(
            label = stringResource(R.string.theme_marketplace_label_description),
            value = entry.description,
        ),
        assets?.previewUrl?.let { url ->
            DetailRow(
                label = stringResource(R.string.theme_marketplace_label_preview),
                value = url,
            )
        },
    )
