package com.m57.hermescontrol.ui.channels

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

// ── State → badge colour / label  ──────────────────────────────────────

private data class StateStyle(val label: String, val color: Color, val icon: ImageVector)

@Composable
private fun platformStateStyle(state: String): StateStyle =
    when (state) {
        "connected" -> StateStyle("Connected", Color(0xFF4CAF50), Icons.Filled.CheckCircle)
        "pending_restart" -> StateStyle("Restart to apply", Color(0xFFFFC107), Icons.Filled.Refresh)
        "gateway_stopped" -> StateStyle("Gateway stopped", Color(0xFFFF9800), Icons.Filled.RadioButtonChecked)
        "startup_failed" -> StateStyle("Start failed", Color(0xFFF44336), Icons.Filled.Warning)
        "disconnected" -> StateStyle("Disconnected", Color(0xFFFF9800), Icons.Filled.RadioButtonChecked)
        "not_configured" -> StateStyle("Not configured", Color(0xFF9E9E9E), Icons.Filled.RadioButtonChecked)
        "disabled" -> StateStyle("Disabled", Color(0xFF9E9E9E), Icons.Filled.RadioButtonChecked)
        "fatal" -> StateStyle("Error", Color(0xFFF44336), Icons.Filled.Warning)
        else -> StateStyle(state, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.RadioButtonChecked)
    }

@Composable
private fun StateBadge(state: String) {
    val style = platformStateStyle(state)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = style.color.copy(alpha = 0.15f),
    ) {
        Text(
            text = style.label,
            color = style.color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ── Main screen  ──────────────────────────────────────────────────────

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
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadPlatforms() },
    ) { paddingValues ->
        when {
            state.isLoading && state.platforms.isEmpty() -> {
                LoadingState(
                    subtitle = stringResource(R.string.loading_state_subtitle_channels),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.errorMessage != null && state.platforms.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlatforms() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.platforms.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.channels_empty_title),
                    subtitle = stringResource(R.string.channels_empty_desc),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                val gatewayRunning = state.platforms.firstOrNull()?.gatewayRunning ?: false

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    // ── Restart banner ──
                    if (state.restartNeeded) {
                        item(key = "restart_banner") {
                            RestartBanner(
                                isRestarting = state.isRestarting,
                                onRestart = viewModel::restartGateway,
                            )
                        }
                    }

                    // ── Gateway-not-running hint ──
                    if (!gatewayRunning && !state.restartNeeded) {
                        item(key = "gateway_offline") {
                            GatewayOfflineBanner()
                        }
                    }

                    // ── Search bar ──
                    item(key = "search") {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search channels…",
                        )
                    }

                    // ── Channel cards ──
                    items(filteredPlatforms, key = { it.id }) { platform ->
                        PlatformCard(
                            platform = platform,
                            isToggling = state.togglingId == platform.id,
                            isTesting = state.testingId == platform.id,
                            onToggle = { enabled -> viewModel.togglePlatform(platform.id, enabled) },
                            onTest = { viewModel.testPlatform(platform.id) },
                            onSave = { env -> viewModel.configurePlatform(platform.id, env) },
                        )
                    }
                }
            }
        }
    }
}

// ── Restart banner  ───────────────────────────────────────────────────

@Composable
private fun RestartBanner(
    isRestarting: Boolean,
    onRestart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.channels_restart_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onRestart,
                enabled = !isRestarting,
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = stringResource(R.string.channels_action_restart_now),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ── Gateway offline banner  ───────────────────────────────────────────

@Composable
private fun GatewayOfflineBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.channels_gateway_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Platform card  ────────────────────────────────────────────────────

@Composable
private fun PlatformCard(
    platform: MessagingPlatform,
    isToggling: Boolean,
    isTesting: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: (MessagingPlatformUpdate) -> Unit,
) {
    var showConfigureForm by remember { mutableStateOf(false) }
    val style = platformStateStyle(platform.state)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Top row: icon + name + badge + switch ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.color,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = platform.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateBadge(state = platform.state)
                            if (platform.id == "telegram") {
                                // reserved for onboarding badge later
                            }
                        }
                    }
                }

                // Toggle switch
                if (isToggling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(
                        checked = platform.enabled,
                        onCheckedChange = onToggle,
                    )
                }
            }

            // ── Description ──
            if (!platform.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = platform.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Error message ──
            if (!platform.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = platform.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ── Action buttons ──
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.channels_action_test),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Button(
                    onClick = { showConfigureForm = !showConfigureForm },
                ) {
                    Text(
                        text =
                            if (showConfigureForm) {
                                stringResource(R.string.action_cancel)
                            } else {
                                stringResource(R.string.channels_action_configure)
                            },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // ── Configure form ──
            if (showConfigureForm) {
                ConfigureForm(platform = platform, onSave = onSave, onClose = { showConfigureForm = false })
            }
        }
    }
}

// ── Configure form  ───────────────────────────────────────────────────

@Composable
private fun ConfigureForm(
    platform: MessagingPlatform,
    onSave: (MessagingPlatformUpdate) -> Unit,
    onClose: () -> Unit,
) {
    val envFields = platform.envVars.orEmpty()
    val inputValues = remember { mutableStateMapOf<String, String>() }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.channels_sec_settings),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(modifier = Modifier.height(8.dp))

    envFields.forEach { field ->
        OutlinedTextField(
            value = inputValues[field.key].orEmpty(),
            onValueChange = { inputValues[field.key] = it },
            label = { Text(field.prompt ?: field.key) },
            placeholder = {
                if (field.isSet) {
                    Text(field.redactedValue ?: "******** (Already Configured)")
                }
            },
            singleLine = true,
            visualTransformation =
                if (field.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onClose,
        ) {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                val filledEnv = inputValues.filterValues { it.isNotBlank() }
                val update =
                    MessagingPlatformUpdate(
                        enabled = platform.enabled,
                        env = filledEnv,
                    )
                onSave(update)
                onClose()
            },
        ) {
            Text(
                text = stringResource(R.string.channels_action_save_settings),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
