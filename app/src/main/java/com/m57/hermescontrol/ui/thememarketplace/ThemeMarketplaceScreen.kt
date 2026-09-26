package com.m57.hermescontrol.ui.thememarketplace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.theme.marketplace.MarketplaceThemeEntry
import com.m57.hermescontrol.data.theme.marketplace.ThemeAssets
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun ThemeMarketplaceScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ThemeMarketplaceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        modifier = modifier,
        title = { Text(text = stringResource(R.string.screen_themes)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        drawerGesturesEnabled = true,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(
                query = uiState.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

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
                        val isApplying = entry.extensionId == uiState.applyingExtensionId
                        val applyError = if (isApplying) uiState.applyError else null
                        ThemeMarketplaceCard(
                            entry = entry,
                            onResolveAssets = viewModel::resolveAssets,
                            onApply = { viewModel.applyTheme(entry) },
                            isApplying = isApplying,
                            applyError = applyError,
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
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(text = stringResource(R.string.theme_marketplace_search_placeholder)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

@Composable
fun ThemeMarketplaceCard(
    entry: MarketplaceThemeEntry,
    onResolveAssets: suspend (String) -> ThemeAssets?,
    onApply: () -> Unit,
    isApplying: Boolean = false,
    applyError: String? = null,
    modifier: Modifier = Modifier,
) {
    var assets by remember(entry.extensionId) { mutableStateOf<ThemeAssets?>(null) }
    // Resolve preview/download lazily, once per row (cached in the repository).
    LaunchedEffect(entry.extensionId) {
        assets = onResolveAssets(entry.extensionId)
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onApply() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previewUrl = assets?.previewUrl
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )
            } else {
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
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
