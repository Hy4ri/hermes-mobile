package com.m57.hermescontrol.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun ChannelsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ChannelsViewModel = viewModel { ChannelsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }

    val filteredPlatforms =
        remember(query, state.platforms) {
            state.platforms.filter { platform ->
                platform.name.contains(query, ignoreCase = true)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadPlatforms()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_channels)) },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadPlatforms() },
    ) {
        when {
            state.isLoading && state.platforms.isEmpty() -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlatforms() },
                )
            }

            state.platforms.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.channels_empty_title),
                    subtitle = stringResource(R.string.channels_empty_desc),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search channels...",
                        )
                    }
                    items(filteredPlatforms, key = { it.id }) { platform ->
                        var showConfigureForm by remember { mutableStateOf(false) }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(text = platform.name, style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            text =
                                                if (platform.enabled) {
                                                    stringResource(
                                                        R.string.channels_status_active,
                                                    )
                                                } else {
                                                    stringResource(R.string.channels_status_disabled)
                                                },
                                            color =
                                                if (platform.enabled) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = platform.enabled,
                                            onCheckedChange = { isChecked ->
                                                viewModel.configurePlatform(
                                                    platform.id,
                                                    MessagingPlatformUpdate(enabled = isChecked),
                                                )
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { showConfigureForm = !showConfigureForm }) {
                                            Text(
                                                if (showConfigureForm) {
                                                    stringResource(
                                                        R.string.action_cancel,
                                                    )
                                                } else {
                                                    stringResource(R.string.channels_action_configure)
                                                },
                                            )
                                        }
                                    }
                                }

                                if (showConfigureForm) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.channels_sec_settings),
                                        style = MaterialTheme.typography.titleMedium,
                                    )

                                    val envFields = platform.envVars.orEmpty()
                                    val inputValues = remember { mutableStateMapOf<String, String>() }

                                    envFields.forEach { field ->
                                        OutlinedTextField(
                                            value = inputValues[field.key].orEmpty(),
                                            onValueChange = { inputValues[field.key] = it },
                                            label = { Text(field.prompt ?: field.key) },
                                            placeholder = {
                                                if (field.isSet) {
                                                    Text(stringResource(R.string.channels_placeholder_configured))
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.configurePlatform(
                                                platform.id,
                                                MessagingPlatformUpdate(
                                                    enabled = platform.enabled,
                                                    env = inputValues.toMap(),
                                                ),
                                            )
                                            showConfigureForm = false
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text(stringResource(R.string.channels_action_save_settings))
                                    }
                                } else {
                                    val envFields = platform.envVars.orEmpty()
                                    if (envFields.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.channels_sec_keys),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        envFields.forEach { field ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = field.key,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    text =
                                                        if (field.isSet) {
                                                            stringResource(
                                                                R.string.channels_status_configured,
                                                            )
                                                        } else {
                                                            stringResource(R.string.channels_status_not_set)
                                                        },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color =
                                                        if (field.isSet) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.error
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
    }
}
