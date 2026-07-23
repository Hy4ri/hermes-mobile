package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.skills.SkillsUiState

@Composable
internal fun HubBrowseView(
    state: SkillsUiState,
    hubQuery: String,
    onHubQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onInstall: (String) -> Unit,
    onPreviewHubSkill: (String) -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
) {
    var showDetail by remember { mutableStateOf<HubSkill?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = hubQuery,
            onValueChange = onHubQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            placeholder = { Text(stringResource(R.string.skills_hub_search_placeholder)) },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hubQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            onHubQueryChange("")
                            onClearSearch()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    }
                    IconButton(
                        onClick = { onSearch(hubQuery) },
                        enabled = hubQuery.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.skills_hub_search_button),
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        if (hubQuery.isNotBlank()) {
                            onSearch(hubQuery)
                        }
                    },
                ),
        )

        when {
            state.isHubSearching -> {
                SkeletonListState()
            }

            state.hubSearchError != null -> {
                ErrorState(
                    message = state.hubSearchError,
                    onRetry = { onSearch(hubQuery) },
                )
            }

            state.hubResults.isEmpty() && hubQuery.isNotBlank() && !state.isHubSearching -> {
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_hub_empty_results),
                    actionLabel = stringResource(R.string.empty_action_retry_search),
                    onAction = { onSearch(hubQuery) },
                )
            }

            state.hubResults.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    itemsIndexed(
                        state.hubResults,
                        key = { index, hubSkill -> "${hubSkill.name}:${hubSkill.source ?: "unknown"}:$index" },
                    ) { _, hubSkill ->
                        HubSkillCard(
                            hubSkill = hubSkill,
                            onInstall = { onInstall(hubSkill.name) },
                            isInstalling = isInstalling && installingSkillName == hubSkill.name,
                            onClick = {
                                showDetail = hubSkill
                                hubSkill.identifier?.let { onPreviewHubSkill(it) }
                            },
                        )
                    }
                }
            }

            else -> {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.skills_hub_search_hint),
                )
            }
        }
    }

    showDetail?.let { hubSkill ->
        val previewReady = state.hubPreviewIdentifier == hubSkill.identifier
        val fullContent =
            if (previewReady) {
                state.hubPreviewContent ?: hubSkill.description
            } else {
                hubSkill.description
            }
        DetailDialog(
            title = hubSkill.name,
            rows =
                listOf(
                    DetailRow(stringResource(R.string.detail_dialog_category), hubSkill.category),
                    DetailRow(stringResource(R.string.detail_dialog_source), hubSkill.source),
                    DetailRow(stringResource(R.string.detail_dialog_description), fullContent),
                    DetailRow(stringResource(R.string.detail_dialog_tags), hubSkill.tags.orEmpty().joinToString(", ")),
                    DetailRow(stringResource(R.string.detail_dialog_trust_level), hubSkill.trustLevel),
                ),
            actions =
                if (previewReady && state.isHubPreviewing) {
                    { CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp)) }
                } else {
                    null
                },
            onDismiss = { showDetail = null },
        )
    }
}

@Composable
private fun HubSkillCard(
    hubSkill: HubSkill,
    onInstall: () -> Unit,
    isInstalling: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // ── Top row: name + source badge ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hubSkill.name,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                hubSkill.source?.let { source ->
                    Spacer(modifier = Modifier.width(6.dp))
                    SourceBadge(source = source)
                }
            }

            // ── Category ──
            hubSkill.category?.let { cat ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = cat,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Description ──
            hubSkill.description?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Install button ──
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onInstall,
                    enabled = !isInstalling,
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.skills_hub_install))
                    }
                }
            }
        }
    }
}
