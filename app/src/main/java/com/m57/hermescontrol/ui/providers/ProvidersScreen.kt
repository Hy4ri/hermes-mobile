package com.m57.hermescontrol.ui.providers

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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.OAuthProvider
import com.m57.hermescontrol.data.model.OAuthStartResponse
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun ProvidersScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ProvidersViewModel = viewModel { ProvidersViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load() }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // Re-auth dialog
    if (state.flowPhase != OAuthFlowPhase.IDLE && state.flowProvider != null) {
        OAuthFlowDialog(
            state = state,
            onCodeChange = viewModel::onCodeInputChange,
            onSubmitCode = viewModel::submitOAuthCode,
            onCancel = viewModel::cancelOAuthFlow,
            onDismissFlow = viewModel::dismissFlow,
            onOpenBrowser = { url ->
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ),
                    )
                } catch (_: Exception) {
                    // No browser available — ignore; user can copy the URL.
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_providers)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
    ) { paddingValues ->
        when {
            state.isLoading && state.providers.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.providers.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.load() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.providers.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.providers_empty_title),
                    subtitle = stringResource(R.string.providers_empty_desc),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.providers, key = { it.id }) { provider ->
                        ProviderCard(
                            provider = provider,
                            isActing = state.actingId == provider.id,
                            flowPhase = state.flowPhase,
                            onConnect = { viewModel.startOAuthFlow(provider) },
                            onDisconnect = { viewModel.disconnectProvider(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: OAuthProvider,
    isActing: Boolean,
    flowPhase: OAuthFlowPhase,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val loggedIn = provider.status.loggedIn
    val isExternal = provider.flow == "external"
    val flowActive = flowPhase != OAuthFlowPhase.IDLE && flowPhase != OAuthFlowPhase.DONE

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = providerStatusIcon(provider),
                contentDescription = null,
                tint = providerStatusColor(provider),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = providerStatusLabel(provider),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                provider.status.sourceLabel?.let { label ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "via $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (flowActive) {
                        OutlinedButton(
                            onClick = { /* in-flight flow handled by dialog */ },
                            enabled = false,
                        ) {
                            Text(
                                text = stringResource(R.string.providers_action_in_progress),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else if (loggedIn) {
                        if (provider.disconnectable) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                enabled = !isActing,
                            ) {
                                Text(
                                    text = stringResource(R.string.providers_action_disconnect),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        } else {
                            provider.disconnectHint?.let { hint ->
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            enabled = !isActing,
                        ) {
                            Text(
                                text =
                                    if (isExternal) {
                                        stringResource(R.string.providers_action_how_to_connect)
                                    } else {
                                        stringResource(R.string.providers_action_connect)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Re-auth flow dialog ─────────────────────────────────────────────────

@Composable
private fun OAuthFlowDialog(
    state: ProvidersUiState,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onCancel: () -> Unit,
    onDismissFlow: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val provider = state.flowProvider ?: return
    val start = state.flowStart

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            when (state.flowPhase) {
                OAuthFlowPhase.WAITING_CODE -> {
                    Button(onClick = onSubmitCode) {
                        Text(stringResource(R.string.providers_action_submit_code))
                    }
                }

                OAuthFlowPhase.DONE -> {
                    Button(onClick = onDismissFlow) {
                        Text(stringResource(R.string.action_done))
                    }
                }

                OAuthFlowPhase.ERROR -> {
                    Button(onClick = onDismissFlow) {
                        Text(stringResource(R.string.action_close))
                    }
                }

                OAuthFlowPhase.SHOW_CLI -> {
                    Button(onClick = onDismissFlow) {
                        Text(stringResource(R.string.action_close))
                    }
                }

                else -> {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        },
        dismissButton = {
            if (state.flowPhase == OAuthFlowPhase.WAITING_CODE) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
        title = { Text(provider.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    provider.flow == "external" -> {
                        ExternalFlowContent(provider = provider)
                    }

                    state.flowPhase == OAuthFlowPhase.WAITING_CODE && start?.authUrl != null -> {
                        PkceFlowContent(
                            authUrl = start.authUrl,
                            code = state.flowCodeInput,
                            onCodeChange = onCodeChange,
                            onOpenBrowser = onOpenBrowser,
                            error = state.flowErrorMessage,
                        )
                    }

                    state.flowPhase == OAuthFlowPhase.POLLING -> {
                        DeviceCodeFlowContent(
                            start = start,
                            status = state.flowStatus,
                            expiresIn = state.flowExpiresIn,
                            error = state.flowErrorMessage,
                        )
                    }

                    state.flowPhase == OAuthFlowPhase.DONE -> {
                        Text(
                            text = stringResource(R.string.providers_flow_success),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    state.flowErrorMessage != null -> {
                        Text(
                            text = state.flowErrorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ExternalFlowContent(provider: OAuthProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.providers_external_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        provider.cliCommand.takeIf { it.isNotBlank() }?.let { cmd ->
            Surface(
                color = LocalHermesStatusColors.current.infoContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = "  $cmd",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PkceFlowContent(
    authUrl: String,
    code: String,
    onCodeChange: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onOpenBrowser(authUrl) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.OpenInBrowser,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.providers_action_open_browser),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = stringResource(R.string.providers_pkce_paste_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.providers_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            visualTransformation = VisualTransformation.None,
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DeviceCodeFlowContent(
    start: OAuthStartResponse?,
    status: String,
    expiresIn: String,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.providers_device_code_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        start?.userCode?.let { code ->
            Surface(
                color = LocalHermesStatusColors.current.infoContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = "  $code",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        start?.verificationUrl?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expiresIn.isNotEmpty()) {
            Text(
                text = stringResource(R.string.providers_expires_in, expiresIn),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val statusLabel = deviceStatusLabel(status)
        if (statusLabel != null) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (status == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── Status helpers ──────────────────────────────────────────────────────

private fun providerStatusIcon(provider: OAuthProvider): ImageVector =
    when {
        provider.status.loggedIn -> Icons.Filled.CheckCircle
        provider.flow == "external" -> Icons.Filled.Link
        else -> Icons.Filled.Warning
    }

@Composable
private fun providerStatusColor(provider: OAuthProvider): Color =
    when {
        provider.status.loggedIn -> LocalHermesStatusColors.current.success
        else -> LocalHermesStatusColors.current.warning
    }

private fun providerStatusLabel(provider: OAuthProvider): String =
    when {
        provider.status.loggedIn -> "Connected"
        provider.flow == "external" -> "External — connect via CLI"
        else -> "Not connected"
    }

private fun deviceStatusLabel(status: String): String? =
    when (status) {
        "pending" -> "Waiting for authorization…"
        "approved" -> "Approved"
        "denied" -> "Denied"
        "expired" -> "Expired"
        "error" -> "Error"
        else -> null
    }
