package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.skills.SkillsUiState

@Composable
fun HubBrowseView(
    state: SkillsUiState,
    hubQuery: String,
    onHubQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onInstall: (String) -> Unit,
    onPreviewHubSkill: (String) -> Unit,
    onScanHubSkill: (String) -> Unit,
    onClearHubScan: () -> Unit,
    onLoadHubSources: () -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
) {
    var hubShowDetail by remember { mutableStateOf<HubSkill?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = hubQuery,
            onValueChange = onHubQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                )
            }

            state.hubResults.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                                hubShowDetail = hubSkill
                                hubSkill.identifier?.let { onPreviewHubSkill(it) }
                            },
                        )
                    }
                }
            }

            else -> {
                HubLandingView(
                    state = state,
                    onLoadHubSources = onLoadHubSources,
                    onInstall = onInstall,
                    isInstalling = isInstalling,
                    installingSkillName = installingSkillName,
                    onOpenDetail = { skill ->
                        hubShowDetail = skill
                        skill.identifier?.let { onPreviewHubSkill(it) }
                    },
                )
            }
        }
    }

    hubShowDetail?.let { hubSkill ->
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
            actions = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (previewReady && state.isHubPreviewing) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    }
                    hubSkill.identifier?.let { identifier ->
                        SkillScanSection(
                            scanResult =
                                state.hubScanResult.takeIf {
                                    state.hubScanIdentifier == identifier
                                },
                            isScanning =
                                state.isHubScanning &&
                                    state.hubScanIdentifier == identifier,
                            scanError =
                                state.hubScanError.takeIf {
                                    state.hubScanIdentifier == identifier
                                },
                            onScan = { onScanHubSkill(identifier) },
                        )
                    }
                }
            },
            onDismiss = {
                hubShowDetail = null
                onClearHubScan()
            },
        )
    }
}

@Composable
fun HubLandingView(
    state: SkillsUiState,
    onLoadHubSources: () -> Unit,
    onInstall: (String) -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
    onOpenDetail: (HubSkill) -> Unit,
) {
    when {
        state.isHubSourcesLoading -> {
            SkeletonListState()
        }

        state.hubSourcesError != null -> {
            ErrorState(
                message = state.hubSourcesError,
                onRetry = onLoadHubSources,
            )
        }

        state.hubSources.isEmpty() && state.hubFeatured.isEmpty() -> {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = stringResource(R.string.skills_hub_landing_hint),
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = listItemSpacing,
            ) {
                if (state.hubSources.isNotEmpty()) {
                    item(key = "hub_sources_header") {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.skills_hub_sources_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier =
                                    Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.hubSources.forEach { source ->
                                    SourceChip(source = source)
                                }
                            }
                        }
                    }
                }

                if (state.hubFeatured.isNotEmpty()) {
                    item(key = "hub_featured_header") {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.skills_hub_featured_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.skills_hub_featured_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(
                        state.hubFeatured,
                        key = { index, hubSkill ->
                            "featured:${hubSkill.name}:${hubSkill.source ?: "unknown"}:$index"
                        },
                    ) { _, hubSkill ->
                        HubSkillCard(
                            hubSkill = hubSkill,
                            onInstall = { onInstall(hubSkill.name) },
                            isInstalling =
                                isInstalling && installingSkillName == hubSkill.name,
                            onClick = { onOpenDetail(hubSkill) },
                        )
                    }
                }
            }
        }
    }
}
