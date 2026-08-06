@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.m57.hermescontrol.ui.toolsets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ToolsetConfigResponse
import com.m57.hermescontrol.data.model.ToolsetEnvVar
import com.m57.hermescontrol.data.model.ToolsetProvider
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

/**
 * Per-toolset management page (issue #782) — the mobile counterpart of the
 * desktop toolset-config-panel: provider pick with honest readiness pills,
 * per-provider API-key entry (save/clear/reveal), and the post-setup install
 * hook with an inlined live log.
 */
@Composable
fun ToolsetDetailScreen(
    name: String,
    label: String?,
    onBack: () -> Unit,
    viewModel: ToolsetDetailViewModel = viewModel { ToolsetDetailViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.config

    var deleteTarget by remember { mutableStateOf<ToolsetEnvVar?>(null) }

    // The VM is shared across all toolset detail entries (activity-level
    // ViewModelStoreOwner) — drive it with the CURRENT entry's toolset.
    LaunchedEffect(name) {
        viewModel.setToolset(name)
        viewModel.loadConfig()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    deleteTarget?.let { envVar ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.toolset_delete_env_title)) },
            text = { Text(stringResource(R.string.toolset_delete_env_message, envVar.key)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        viewModel.clearEnvVar(envVar.key)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.toolset_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(label ?: name) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadConfig() },
    ) { paddingValues ->
        when {
            state.isLoading && config == null -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && config == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadConfig() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            config == null -> {
                EmptyState(
                    title = stringResource(R.string.toolsets_empty_title),
                    subtitle = stringResource(R.string.toolsets_empty_desc),
                    onAction = { viewModel.loadConfig() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                ToolsetConfigContent(
                    config = config,
                    state = state,
                    onToggleExpanded = viewModel::toggleProviderExpanded,
                    onSelectProvider = viewModel::selectProvider,
                    onSaveEnv = viewModel::saveEnvVar,
                    onRequestDeleteEnv = { deleteTarget = it },
                    onRevealEnv = viewModel::revealEnvVar,
                    onHideEnv = viewModel::hideEnvVar,
                    onRunPostSetup = viewModel::runPostSetup,
                )
            }
        }
    }
}

@Composable
private fun ToolsetConfigContent(
    config: ToolsetConfigResponse,
    state: ToolsetDetailUiState,
    onToggleExpanded: (String) -> Unit,
    onSelectProvider: (ToolsetProvider) -> Unit,
    onSaveEnv: (String, String) -> Unit,
    onRequestDeleteEnv: (ToolsetEnvVar) -> Unit,
    onRevealEnv: (String) -> Unit,
    onHideEnv: (String) -> Unit,
    onRunPostSetup: (String) -> Unit,
) {
    val noBackendsText = stringResource(R.string.toolset_detail_no_backends)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        if (config.activeSearchBackend != null || config.activeExtractBackend != null) {
            item(key = "web-capabilities") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    config.activeSearchBackend?.let {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.toolset_web_search_active, it)) },
                        )
                    }
                    config.activeExtractBackend?.let {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.toolset_web_extract_active, it)) },
                        )
                    }
                }
            }
        }

        if (config.providers.isEmpty()) {
            item(key = "no-backends") {
                Text(
                    text = noBackendsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(config.providers, key = { "provider-${it.name}" }) { provider ->
                ToolsetProviderCard(
                    provider = provider,
                    expanded = state.expandedProvider == provider.name,
                    selecting = state.selectingProvider == provider.name,
                    savingEnvKey = state.savingEnvKey,
                    deletingEnvKey = state.deletingEnvKey,
                    revealedValues = state.revealedValues,
                    postSetup = state.postSetup,
                    onToggleExpanded = { onToggleExpanded(provider.name) },
                    onSelectProvider = { onSelectProvider(provider) },
                    onSaveEnv = onSaveEnv,
                    onRequestDeleteEnv = onRequestDeleteEnv,
                    onRevealEnv = onRevealEnv,
                    onHideEnv = onHideEnv,
                    onRunPostSetup = onRunPostSetup,
                )
            }
        }
    }
}

@Composable
private fun ToolsetProviderCard(
    provider: ToolsetProvider,
    expanded: Boolean,
    selecting: Boolean,
    savingEnvKey: String?,
    deletingEnvKey: String?,
    revealedValues: Map<String, String>,
    postSetup: PostSetupState?,
    onToggleExpanded: () -> Unit,
    onSelectProvider: () -> Unit,
    onSaveEnv: (String, String) -> Unit,
    onRequestDeleteEnv: (ToolsetEnvVar) -> Unit,
    onRevealEnv: (String) -> Unit,
    onHideEnv: (String) -> Unit,
    onRunPostSetup: (String) -> Unit,
) {
    val statusText = stringResource(provider.statusTextRes())
    val statusType = provider.statusType()
    val activeText = stringResource(R.string.toolset_active_badge)
    val noKeysText = stringResource(R.string.toolset_no_api_key_required)
    val useBackendText = stringResource(R.string.toolset_action_use_backend)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (expanded) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Header (tap to expand/collapse) ────────────────────────
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        provider.badge?.takeIf { it.isNotBlank() }?.let {
                            StatusBadge(text = it, status = StatusBadgeType.NEUTRAL)
                        }
                        if (provider.isActive) {
                            StatusBadge(text = activeText, status = StatusBadgeType.SUCCESS)
                        }
                        if (statusText.isNotEmpty()) {
                            StatusBadge(text = statusText, status = statusType)
                        }
                    }
                }
                if (selecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            // ── Expanded body ──────────────────────────────────────────
            if (expanded) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    provider.tag?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!provider.isActive) {
                        Button(onClick = onSelectProvider, enabled = !selecting) {
                            Text(useBackendText)
                        }
                    }

                    if (provider.requiresNousAuth) {
                        Text(
                            text = stringResource(R.string.toolset_nous_signin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (provider.envVars.isEmpty()) {
                        Text(
                            text = noKeysText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        provider.envVars.forEach { envVar ->
                            ToolsetEnvVarRow(
                                envVar = envVar,
                                saving = savingEnvKey == envVar.key,
                                deleting = deletingEnvKey == envVar.key,
                                revealedValue = revealedValues[envVar.key],
                                onSave = { value -> onSaveEnv(envVar.key, value) },
                                onRequestDelete = { onRequestDeleteEnv(envVar) },
                                onReveal = { onRevealEnv(envVar.key) },
                                onHide = { onHideEnv(envVar.key) },
                            )
                        }
                    }

                    provider.postSetup?.let { postSetupKey ->
                        PostSetupRunner(
                            postSetupKey = postSetupKey,
                            installed = provider.status == "ready",
                            postSetup = postSetup,
                            onRun = { onRunPostSetup(postSetupKey) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsetEnvVarRow(
    envVar: ToolsetEnvVar,
    saving: Boolean,
    deleting: Boolean,
    revealedValue: String?,
    onSave: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onReveal: () -> Unit,
    onHide: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    val setText = stringResource(R.string.toolset_env_set)
    val notSetText = stringResource(R.string.toolset_env_not_set)
    val saveText = stringResource(R.string.toolset_env_save)
    val cancelText = stringResource(R.string.action_cancel)
    val editDesc = stringResource(R.string.toolset_edit_env_desc, envVar.key)
    val revealDesc = stringResource(R.string.toolset_reveal_env_desc, envVar.key)
    val hideDesc = stringResource(R.string.toolset_hide_env_desc, envVar.key)
    val clearDesc = stringResource(R.string.toolset_clear_env_desc, envVar.key)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = envVar.key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    envVar.prompt?.takeIf { it.isNotBlank() && it != envVar.key }?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusBadge(
                    text = if (envVar.isSet) setText else notSetText,
                    status = if (envVar.isSet) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                )
            }

            if (!editing) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { editing = true },
                        enabled = !saving && !deleting,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = editDesc,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (revealedValue == null) {
                        IconButton(
                            onClick = onReveal,
                            enabled = envVar.isSet && !saving && !deleting,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = revealDesc,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onHide,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VisibilityOff,
                                contentDescription = hideDesc,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (envVar.isSet) {
                        IconButton(
                            onClick = onRequestDelete,
                            enabled = !saving && !deleting,
                            modifier = Modifier.size(36.dp),
                        ) {
                            if (deleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = clearDesc,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            revealedValue?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (editing) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(envVar.key) },
                    placeholder = { Text(envVar.prompt ?: envVar.key) },
                    visualTransformation =
                        if (revealedValue != null) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                        ),
                    enabled = !saving,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = {
                            onSave(value)
                            value = ""
                            editing = false
                        },
                        enabled = !saving && value.isNotBlank(),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(saveText)
                    }
                    TextButton(
                        onClick = {
                            editing = false
                            value = ""
                        },
                        enabled = !saving,
                    ) {
                        Text(cancelText)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostSetupRunner(
    postSetupKey: String,
    installed: Boolean,
    postSetup: PostSetupState?,
    onRun: () -> Unit,
) {
    val hintText =
        if (installed) {
            stringResource(R.string.toolset_post_setup_installed_hint)
        } else {
            stringResource(R.string.toolset_post_setup_hint)
        }
    val installedText = stringResource(R.string.toolset_post_setup_installed)
    val runText = stringResource(R.string.toolset_post_setup_run)
    val rerunText = stringResource(R.string.toolset_post_setup_rerun)
    val runningText = stringResource(R.string.toolset_post_setup_running)

    val activeState = postSetup?.takeIf { it.key == postSetupKey }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    activeState?.exitCode?.let { code ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text =
                                if (code == 0) {
                                    stringResource(R.string.toolset_post_setup_done_ok, code)
                                } else {
                                    stringResource(R.string.toolset_post_setup_done_fail, code)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color =
                                if (code == 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }
                }
                if (installed && activeState == null) {
                    StatusBadge(text = installedText, status = StatusBadgeType.SUCCESS)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (activeState?.running == true) {
                    Button(onClick = {}, enabled = false) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(runningText)
                    }
                } else {
                    Button(onClick = onRun) {
                        Text(if (installed) rerunText else runText)
                    }
                }
            }

            val lines = activeState?.lines.orEmpty()
            if (activeState?.running == true || lines.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                    ) {
                        Text(
                            text = if (lines.isEmpty()) runningText else lines.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun ToolsetProvider.statusTextRes(): Int =
    when (status) {
        "ready" -> R.string.toolset_status_ready
        "needs_keys" -> R.string.toolset_status_needs_keys
        "needs_auth" -> R.string.toolset_status_needs_auth
        "needs_setup" -> R.string.toolset_status_needs_setup
        else ->
            // Older backends may omit `status` — fall back to the legacy
            // env-var heuristic the desktop used before server-computed
            // readiness landed.
            if (envVars.isEmpty() || envVars.all { it.isSet }) {
                R.string.toolset_status_ready
            } else {
                R.string.toolset_status_needs_keys
            }
    }

private fun ToolsetProvider.statusType(): StatusBadgeType =
    when (status) {
        "ready" -> StatusBadgeType.SUCCESS
        "needs_keys", "needs_auth", "needs_setup" -> StatusBadgeType.WARNING
        else ->
            if (envVars.isEmpty() || envVars.all { it.isSet }) {
                StatusBadgeType.SUCCESS
            } else {
                StatusBadgeType.WARNING
            }
    }
