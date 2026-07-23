@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.m57.hermescontrol.ui.keys

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Apps
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect

private const val FILTER_ALL = "ALL"

private val keysContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val keysItemSpacing = Arrangement.spacedBy(12.dp)

@Composable
fun KeysScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KeysViewModel = viewModel { KeysViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(FILTER_ALL) }

    val filterAllLabel = stringResource(R.string.keys_filter_all)

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

    val visibleCategories =
        remember(filteredCategories, selectedCategory) {
            if (selectedCategory == FILTER_ALL) {
                filteredCategories
            } else {
                filteredCategories.filter { it.name == selectedCategory }
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
                        contentPadding = keysContentPadding,
                        verticalArrangement = keysItemSpacing,
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

                        // ── 3. Category Filter Chips ─────────────────────────────
                        item(key = "filter-bar") {
                            CategoryFilterBar(
                                categories = state.categories,
                                selectedCategory = selectedCategory,
                                onCategorySelected = { selectedCategory = it },
                                filterAllLabel = filterAllLabel,
                            )
                        }

                        // ── 4. Keys List ─────────────────────────────────────────
                        if (visibleCategories.isEmpty()) {
                            item(key = "no-results") {
                                Box(
                                    modifier = Modifier.fillParentMaxHeight(0.6f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EmptyState(
                                        title = stringResource(R.string.keys_no_matching_title),
                                        subtitle = stringResource(R.string.keys_no_matching_desc),
                                        actionLabel = stringResource(R.string.empty_action_clear_search),
                                        onAction = {
                                            query = ""
                                            selectedCategory = FILTER_ALL
                                        },
                                    )
                                }
                            }
                        }

                        visibleCategories.forEach { section ->
                            if (selectedCategory == FILTER_ALL && filteredCategories.size > 1) {
                                item(key = "category-section-${section.name}") {
                                    SectionHeader(title = "${section.name} (${section.vars.size})")
                                }
                            }

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

// ── Category Filter Bar ──────────────────────────────────────────────────────

@Composable
private fun CategoryFilterBar(
    categories: List<CategorySection>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    filterAllLabel: String,
    modifier: Modifier = Modifier,
) {
    val totalCount = remember(categories) { categories.sumOf { it.vars.size } }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        item(key = "filter-all") {
            FilterChip(
                selected = selectedCategory == FILTER_ALL,
                onClick = { onCategorySelected(FILTER_ALL) },
                label = { Text("$filterAllLabel ($totalCount)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }

        items(
            items = categories,
            key = { section -> "filter-${section.name}" },
        ) { section ->
            val icon: ImageVector =
                when (section.name) {
                    "LLM Providers" -> Icons.Outlined.SmartToy
                    "Tool API Keys" -> Icons.Outlined.Build
                    "Messaging Platforms" -> Icons.Outlined.Forum
                    "Agent Settings" -> Icons.Outlined.Tune
                    else -> Icons.Outlined.Folder
                }

            FilterChip(
                selected = selectedCategory == section.name,
                onClick = { onCategorySelected(section.name) },
                label = { Text("${section.name} (${section.vars.size})") },
                leadingIcon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
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

    // Insert zero-width spaces (\u200B) after underscores so line breaks happen cleanly between words
    val formattedKeyName = remember(key) { key.replace("_", "_\u200B") }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Key name + Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formattedKeyName,
                        style =
                            if (key.length > 24) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

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

                    // Description
                    if (!config.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
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
                Spacer(modifier = Modifier.height(6.dp))
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

            Spacer(modifier = Modifier.height(10.dp))

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
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
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
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, statusColors.warning.copy(alpha = 0.4f)),
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
