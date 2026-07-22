package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar

/**
 * Reusable model picker used by both the global model screen and the
 * in-session `/model` hot-swap (issue #589). Selecting a provider/model pair
 * invokes [onSelect]; the consumer decides what to do with it (global
 * `config.yaml` assignment vs. a session-scoped `/model` slash command).
 *
 * [pinnedModels] renders a pinned section at the top (mirrors the global model
 * screen's pin section) so frequently-used models are one tap away.
 * [onPinToggle] allows pinning and unpinning models directly inside the dialog.
 */
@Composable
fun ModelPickerDialog(
    providers: List<ModelProvider>,
    title: String,
    isLoading: Boolean = false,
    pinnedModels: List<PinnedModel> = emptyList(),
    onPinToggle: ((provider: String, model: String) -> Unit)? = null,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        text = {
            Column {
                if (isLoading) {
                    LoadingState(
                        subtitle = "Loading models…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (providers.isEmpty() && pinnedModels.isEmpty()) {
                    Text(
                        text = "No models available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val filteredPinned =
                        remember(pickerQuery, pinnedModels) {
                            if (pickerQuery.isBlank()) {
                                pinnedModels
                            } else {
                                pinnedModels.filter {
                                    it.modelName.contains(pickerQuery, ignoreCase = true) ||
                                        it.providerSlug.contains(pickerQuery, ignoreCase = true)
                                }
                            }
                        }

                    val pinnedSet =
                        remember(pinnedModels) {
                            pinnedModels.map { "${it.providerSlug}:${it.modelName}" }.toSet()
                        }

                    val filteredProvidersWithModels =
                        remember(pickerQuery, providers) {
                            providers.mapNotNull { provider ->
                                val matchingModels =
                                    provider.models.orEmpty().filter { model ->
                                        pickerQuery.isBlank() ||
                                            provider.name.contains(pickerQuery, ignoreCase = true) ||
                                            provider.slug.contains(pickerQuery, ignoreCase = true) ||
                                            model.contains(pickerQuery, ignoreCase = true)
                                    }
                                if (matchingModels.isNotEmpty()) {
                                    provider to matchingModels
                                } else {
                                    null
                                }
                            }
                        }

                    SearchBar(
                        query = pickerQuery,
                        onQueryChange = { pickerQuery = it },
                        placeholder = "Search models and providers...",
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // ── Pinned section ──
                        if (filteredPinned.isNotEmpty()) {
                            item(key = "pinned-header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PushPin,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Pinned",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            items(
                                filteredPinned,
                                key = { "pinned:${it.providerSlug}:${it.modelName}" },
                            ) { pinned ->
                                ModelItemCard(
                                    modelName = pinned.modelName,
                                    isPinned = true,
                                    onPinToggle =
                                        if (onPinToggle != null) {
                                            { onPinToggle(pinned.providerSlug, pinned.modelName) }
                                        } else {
                                            null
                                        },
                                    onClick = { onSelect(pinned.providerSlug, pinned.modelName) },
                                )
                            }
                        }

                        // ── All providers / models (lazy item per model) ──
                        filteredProvidersWithModels.forEach { (provider, models) ->
                            item(key = "header:${provider.slug}") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                                ) {
                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${models.size} models",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            items(
                                items = models,
                                key = { model -> "${provider.slug}:$model" },
                            ) { model ->
                                val isPinned = "${provider.slug}:$model" in pinnedSet
                                ModelItemCard(
                                    modelName = model,
                                    isPinned = isPinned,
                                    onPinToggle =
                                        if (onPinToggle != null) {
                                            { onPinToggle(provider.slug, model) }
                                        } else {
                                            null
                                        },
                                    onClick = { onSelect(provider.slug, model) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ModelItemCard(
    modelName: String,
    isPinned: Boolean,
    onPinToggle: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onPinToggle != null) {
                IconButton(
                    onClick = onPinToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin model" else "Pin model",
                        tint =
                            if (isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
