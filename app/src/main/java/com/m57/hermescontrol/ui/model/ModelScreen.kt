package com.m57.hermescontrol.ui.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ModelViewModel = viewModel { ModelViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expandedProviderSlug by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val filteredProviders =
        remember(query, state.providers) {
            state.providers.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.slug.contains(query, ignoreCase = true) ||
                    provider.models.orEmpty().any { model ->
                        model.contains(query, ignoreCase = true)
                    }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadModelOptions()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_models)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadModelOptions() },
    ) { paddingValues ->
        when {
            state.isLoading && state.providers.isEmpty() -> {
                LoadingState(
                    subtitle = stringResource(R.string.loading_state_subtitle_models),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadModelOptions() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.providers.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.model_empty_title),
                    subtitle = stringResource(R.string.model_empty_desc),
                    onAction = { viewModel.loadModelOptions() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search models...",
                        )
                    }
                    items(filteredProviders, key = { it.slug }) { provider ->
                        val isExpanded = expandedProviderSlug == provider.slug
                        val isCurrent = provider.is_current == true

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                expandedProviderSlug = if (isExpanded) null else provider.slug
                            },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (isCurrent) {
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
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = provider.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = stringResource(R.string.model_label_slug, provider.slug),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    if (isCurrent) {
                                        Text(
                                            text = stringResource(R.string.model_status_current),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                provider.warning?.let {
                                    if (it.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = isExpanded) {
                                    Column(modifier = Modifier.padding(top = 16.dp)) {
                                        Text(
                                            text = stringResource(R.string.model_label_available),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )

                                        val models = provider.models.orEmpty()
                                        val filteredModels =
                                            if (query.isBlank()) {
                                                models
                                            } else {
                                                models.filter {
                                                    it.contains(query, ignoreCase = true)
                                                }
                                            }
                                        if (filteredModels.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.model_no_models),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            filteredModels.forEach { model ->
                                                val isActive =
                                                    state.activeProfile?.provider == provider.slug &&
                                                        state.activeProfile?.model == model
                                                Card(
                                                    onClick = {
                                                        viewModel.selectModel(provider.slug, model)
                                                    },
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp),
                                                    colors =
                                                        CardDefaults.cardColors(
                                                            containerColor =
                                                                if (isActive) {
                                                                    MaterialTheme.colorScheme
                                                                        .primaryContainer
                                                                        .copy(
                                                                            alpha = 0.3f,
                                                                        )
                                                                } else {
                                                                    MaterialTheme.colorScheme.surface
                                                                },
                                                        ),
                                                    border =
                                                        BorderStroke(
                                                            width = 1.dp,
                                                            color =
                                                                if (isActive) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.outline.copy(
                                                                        alpha = 0.2f,
                                                                    )
                                                                },
                                                        ),
                                                ) {
                                                    Row(
                                                        modifier =
                                                            Modifier
                                                                .fillMaxWidth()
                                                                .padding(12.dp),
                                                        horizontalArrangement =
                                                            Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            text = model,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight =
                                                                if (isActive) {
                                                                    FontWeight.Bold
                                                                } else {
                                                                    FontWeight.Normal
                                                                },
                                                            color =
                                                                if (isActive) {
                                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                                } else {
                                                                    MaterialTheme.colorScheme.onSurface
                                                                },
                                                        )
                                                        if (isActive) {
                                                            Icon(
                                                                imageVector = Icons.Filled.Check,
                                                                contentDescription =
                                                                    stringResource(
                                                                        R.string.content_desc_active_model,
                                                                    ),
                                                                tint = MaterialTheme.colorScheme.primary,
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
                }
            }
        }
    }
}
