package com.m57.hermescontrol.ui.keys

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
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

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_keys)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadKeys() },
    ) { paddingValues ->
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
                    // ── 1. Search ────────────────────────────────────────────
                    item(key = "search") {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.keys_search_placeholder),
                        )
                    }

                    // ── 2. Add new key form ─────────────────────────────────
                    item(key = "add-key") {
                        AddKeySection(
                            newKeyName = state.newKeyName,
                            newKeyValue = state.newKeyValue,
                            isAdding = state.isAddingKey,
                            onNameChange = viewModel::setNewKeyName,
                            onValueChange = viewModel::setNewKeyValue,
                            onAdd = viewModel::addKey,
                        )
                    }

                    // ── 3. Categorized sections ──────────────────────────────
                    if (filteredCategories.isEmpty() && query.isNotBlank()) {
                        item(key = "no-results") {
                            EmptyState(
                                title = "No matching keys",
                                subtitle = "Try a different search term.",
                                modifier = Modifier.padding(paddingValues),
                            )
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
                                    onDelete = { viewModel.deleteKey(key) },
                                )
                            }
                        }
                    }

                    // ── 4. Restart banner ────────────────────────────────────
                    if (state.keysChanged) {
                        item(key = "restart-banner") {
                            RestartBanner(
                                isRestarting = state.isRestartingGateway,
                                onRestart = viewModel::restartGateway,
                                onDismiss = { /* changes stay dirty until restart */ },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Add new key form ─────────────────────────────────────────────────────────

@Composable
private fun AddKeySection(
    newKeyName: String,
    newKeyValue: String,
    isAdding: Boolean,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionHeader(title = stringResource(R.string.keys_add_header))
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = newKeyName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.keys_add_key_label)) },
                    placeholder = { Text("MY_API_KEY") },
                    enabled = !isAdding,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newKeyValue,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.keys_add_value_label)) },
                    placeholder = { Text("sk-...") },
                    enabled = !isAdding,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAdding && newKeyName.isNotBlank() && newKeyValue.isNotBlank(),
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.keys_add_action))
                }
            }
        }
    }
}

// ── Category header ──────────────────────────────────────────────────────────

@Composable
private fun CategoryHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ── Env var card ──────────────────────────────────────────────────────────────

@Composable
private fun EnvVarCard(
    key: String,
    config: com.m57.hermescontrol.data.model.EnvVarConfig,
    revealedValue: String?,
    isDeleting: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    val isRevealed = revealedValue != null
    var editedValue by remember(config, revealedValue) { mutableStateOf(revealedValue ?: "") }

    val displayValue =
        if (isRevealed) {
            revealedValue.orEmpty()
        } else if (config.isSet) {
            config.redactedValue ?: "********"
        } else {
            stringResource(R.string.keys_label_not_configured)
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Key name + delete button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!isEditing) {
                    IconButton(
                        onClick = onDelete,
                        enabled = !isDeleting,
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription =
                                    stringResource(
                                        R.string.content_desc_delete_key,
                                    ),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Description
            if (!config.description.isNullOrBlank()) {
                Text(
                    text = config.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Provider link
            if (!config.url.isNullOrBlank()) {
                Text(
                    text = config.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Value display / edit
            if (isEditing) {
                OutlinedTextField(
                    value = editedValue,
                    onValueChange = { editedValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.keys_add_value_label)) },
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
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    contentDescription =
                                        stringResource(
                                            R.string.keys_action_toggle_visibility,
                                        ),
                                )
                            }
                        }
                        IconButton(onClick = {
                            editedValue = if (isRevealed) revealedValue.orEmpty() else ""
                            isEditing = true
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.content_desc_edit),
                            )
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
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.keys_restart_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.keys_restart_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onRestart,
                enabled = !isRestarting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ),
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.keys_restart_action))
                }
            }
        }
    }
}
