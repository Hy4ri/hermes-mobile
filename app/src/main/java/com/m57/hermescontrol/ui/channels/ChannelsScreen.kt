package com.m57.hermescontrol.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState

@Composable
fun ChannelsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ChannelsViewModel = viewModel { ChannelsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadPlatforms()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = "Messaging Channels",
        onOpenDrawer = onOpenDrawer,
        onRefresh = { viewModel.loadPlatforms() },
    ) { paddingValues ->
        when {
            state.isLoading -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }
            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlatforms() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            state.platforms.isEmpty() -> {
                EmptyState(
                    title = "No channels configured",
                    subtitle = "Add messaging platforms in Hermes to see them here.",
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.platforms, key = { it.id }) { platform ->
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
                                            text = if (platform.enabled) "Active" else "Disabled",
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
                                            Text(if (showConfigureForm) "Cancel" else "Configure")
                                        }
                                    }
                                }

                                if (showConfigureForm) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Configuration Settings",
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
                                                    Text("******** (Already Configured)")
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.configurePlatform(platform.id, inputValues.toMap())
                                            showConfigureForm = false
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text("Save Settings")
                                    }
                                } else {
                                    val envFields = platform.envVars.orEmpty()
                                    if (envFields.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = "Configured Keys:", style = MaterialTheme.typography.titleSmall)
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
                                                    text = if (field.isSet) "Configured" else "Not set",
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
