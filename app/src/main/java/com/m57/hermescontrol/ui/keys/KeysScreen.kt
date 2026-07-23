package com.m57.hermescontrol.ui.keys

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KeysViewModel = viewModel { KeysViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filteredCategories =
        remember(query, state.categories) {
            if (query.isBlank()) {
                state.categories
            } else {
                state.categories.mapNotNull { section ->
                    val filtered =
                        section.vars.filter { (key, config) ->
                            key.contains(query, ignoreCase = true) ||
                                config.description?.contains(query, ignoreCase = true) == true
                        }
                    if (filtered.isNotEmpty()) {
                        section.copy(vars = filtered)
                    } else {
                        null
                    }
                }
            }
        }

    val hasAnyVars = state.categories.any { it.vars.isNotEmpty() }

    LaunchedEffect(Unit) {
        viewModel.loadKeys()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // ── Delete Confirmation Dialog ──────────────────────────────────────────
    state.deleteTargetKey?.let { key ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.keys_dialog_delete_title))
                }
            },
            text = {
                Text(stringResource(R.string.keys_dialog_delete_message, key))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteKey() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.keys_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ── Add Key Dialog ──────────────────────────────────────────────────────
    if (state.showAddDialog) {
        AddKeyDialog(
            newKeyName = state.newKeyName,
            newKeyValue = state.newKeyValue,
            isAdding = state.isAddingKey,
            onNameChange = viewModel::setNewKeyName,
            onValueChange = viewModel::setNewKeyValue,
            onAdd = viewModel::addKey,
            onDismiss = viewModel::dismissAddDialog,
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_keys)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadKeys() },
        actions = {
            IconButton(onClick = { viewModel.openAddDialog() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.content_desc_add_key),
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && !hasAnyVars -> {
                    SkeletonListState(modifier = Modifier.padding(paddingValues))
                }

                state.errorMessage != null && !hasAnyVars -> {
                    ErrorState(
                        message = state.errorMessage ?: "",
                        onRetry = { viewModel.loadKeys() },
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                !hasAnyVars && query.isBlank() -> {
                    EmptyState(
                        title = stringResource(R.string.keys_empty_title),
                        subtitle = stringResource(R.string.keys_empty_desc),
                        onAction = { viewModel.loadKeys() },
                        actionLabel = stringResource(R.string.content_desc_refresh),
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                        verticalArrangement = listItemSpacing,
                    ) {
                        // ── 1. Restart banner (pinned at top when dirty) ─────────
                        if (state.keysChanged) {
                            item(key = "restart-banner") {
                                RestartBanner(
                                    isRestarting = state.isRestartingGateway,
                                    onRestart = viewModel::restartGateway,
                                )
                            }
                        }

                        // ── 2. Search Bar ────────────────────────────────────────
                        item(key = "search") {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.keys_search_placeholder),
                            )
                        }

                        // ── 3. Categorized Sections ──────────────────────────────
                        if (filteredCategories.isEmpty() && query.isNotBlank()) {
                            item(key = "no-results") {
                                Box(
                                    modifier = Modifier.fillParentMaxHeight(0.6f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EmptyState(
                                        title = stringResource(R.string.keys_no_matching_title),
                                        subtitle = stringResource(R.string.keys_no_matching_desc),
                                        actionLabel = stringResource(R.string.empty_action_clear_search),
                                        onAction = { query = "" },
                                    )
                                }
                            }
                        }

                        filteredCategories.forEach { section ->
                            item(key = "category-header-${section.name}") {
                                CategoryHeader(
                                    name = section.name,
                                    count = section.vars.size,
                                    expanded = section.expanded,
                                    onToggle = { viewModel.toggleCategory(section.name) },
                                )
                            }

                            if (section.expanded) {
                                items(
                                    items = section.vars.toList(),
                                    key = { (key, _) -> "key-$key" },
                                ) { (key, config) ->
                                    EnvVarCard(
                                        key = key,
                                        config = config,
                                        revealedValue = state.revealedValues[key],
                                        isDeleting = key in state.deletingKeys,
                                        onReveal = { viewModel.revealKey(key) },
                                        onHide = { viewModel.hideKey(key) },
                                        onSave = { value -> viewModel.updateKey(key, value) },
                                        onRequestDelete = { viewModel.requestDeleteKey(key) },
                                        onShowToast = { msg -> viewModel.showToast(msg) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { viewModel.openAddDialog() },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.content_desc_add_key),
                )
            }
        }
    }
}

// ── Add key dialog ───────────────────────────────────────────────────────────

@Composable
private fun AddKeyDialog(
    newKeyName: String,
    newKeyValue: String,
    isAdding: Boolean,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var valueVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.keys_dialog_add_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newKeyName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.keys_add_key_label)) },
                    placeholder = { Text(stringResource(R.string.keys_add_key_placeholder)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    trailingIcon = {
                        if (newKeyName.isNotEmpty() && !isAdding) {
                            IconButton(onClick = { onNameChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = null)
                            }
                        }
                    },
                    enabled = !isAdding,
                )

                OutlinedTextField(
                    value = newKeyValue,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.keys_add_value_label)) },
                    placeholder = { Text(stringResource(R.string.keys_add_value_placeholder)) },
                    visualTransformation =
                        if (valueVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {
                        IconButton(onClick = { valueVisible = !valueVisible }) {
                            Icon(
                                imageVector =
                                    if (valueVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = !isAdding,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAdd,
                enabled = !isAdding && newKeyName.isNotBlank() && newKeyValue.isNotBlank(),
            ) {
                if (isAdding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.keys_add_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAdding,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ── Category header ──────────────────────────────────────────────────────────

@Composable
private fun CategoryHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val categoryIcon: ImageVector =
        when (name) {
            "LLM Providers" -> Icons.Outlined.SmartToy
            "Tool API Keys" -> Icons.Outlined.Build
            "Messaging Platforms" -> Icons.Outlined.Forum
            "Agent Settings" -> Icons.Outlined.Tune
            else -> Icons.Outlined.Folder
        }

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = categoryIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Env var card ──────────────────────────────────────────────────────────────

@Composable
private fun EnvVarCard(
    key: String,
    config: EnvVarConfig,
    revealedValue: String?,
    isDeleting: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onSave: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onShowToast: (String) -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var valueVisible by remember { mutableStateOf(false) }
    val isRevealed = revealedValue != null
    var editedValue by remember(config, revealedValue) { mutableStateOf(revealedValue ?: "") }

    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val displayValue =
        if (isRevealed) {
            revealedValue.orEmpty()
        } else if (config.isSet) {
            config.redactedValue ?: "••••••••••••"
        } else {
            stringResource(R.string.keys_label_not_configured)
        }

    val copiedMessage = stringResource(R.string.keys_copied_toast)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Key name + Status Badge + Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        StatusBadge(
                            text =
                                if (config.isSet) {
                                    stringResource(R.string.keys_status_configured)
                                } else {
                                    stringResource(R.string.keys_status_not_set)
                                },
                            status =
                                if (config.isSet) {
                                    StatusBadgeType.SUCCESS
                                } else {
                                    StatusBadgeType.WARNING
                                },
                        )
                    }

                    // Description
                    if (!config.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = config.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }

                if (!isEditing) {
                    IconButton(
                        onClick = onRequestDelete,
                        enabled = !isDeleting,
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.content_desc_delete_key),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // External documentation link button
            if (!config.url.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clickable {
                                try {
                                    uriHandler.openUri(config.url)
                                } catch (_: Exception) {
                                    // ignore invalid URLs
                                }
                            }
                            .padding(vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.keys_action_open_url),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = config.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Value Display Box or Edit Form
            if (isEditing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editedValue,
                        onValueChange = { editedValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.keys_add_value_label)) },
                        visualTransformation =
                            if (valueVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            IconButton(onClick = { valueVisible = !valueVisible }) {
                                Icon(
                                    imageVector =
                                        if (valueVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = { isEditing = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onSave(editedValue)
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayValue,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (config.isSet) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Row {
                            if (config.isSet) {
                                IconButton(onClick = if (isRevealed) onHide else onReveal) {
                                    Icon(
                                        imageVector =
                                            if (isRevealed) {
                                                Icons.Filled.VisibilityOff
                                            } else {
                                                Icons.Filled.Visibility
                                            },
                                        contentDescription = stringResource(R.string.keys_action_toggle_visibility),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isRevealed && !revealedValue.isNullOrEmpty()) {
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(revealedValue))
                                        onShowToast(copiedMessage)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = stringResource(R.string.keys_action_copy),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                editedValue = if (isRevealed) revealedValue.orEmpty() else ""
                                isEditing = true
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.content_desc_edit),
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

// ── Restart banner ────────────────────────────────────────────────────────────

@Composable
private fun RestartBanner(
    isRestarting: Boolean,
    onRestart: () -> Unit,
) {
    val statusColors = LocalHermesStatusColors.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = statusColors.warningContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.keys_restart_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColors.warning,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.keys_restart_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning.copy(alpha = 0.9f),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onRestart,
                enabled = !isRestarting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = statusColors.warning,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.keys_restart_action))
                }
            }
        }
    }
}
