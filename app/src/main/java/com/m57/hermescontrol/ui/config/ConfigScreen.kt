package com.m57.hermescontrol.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SchemaField
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ConfigViewModel = viewModel { ConfigViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.config_screen_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll() },
    ) { paddingValues ->
        when {
            state.isLoading && state.config == null -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.config == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadAll() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                ConfigContent(
                    state = state,
                    onModeToggle = viewModel::toggleYamlMode,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    onCategoryChange = viewModel::setActiveCategory,
                    onFieldChange = viewModel::updateField,
                    onSave = viewModel::saveConfig,
                    onYamlTextChange = viewModel::setYamlText,
                    onYamlSave = viewModel::saveYamlConfig,
                    onResetCategory = viewModel::resetCategoryToDefaults,
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun ConfigContent(
    state: ConfigUiState,
    onModeToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onFieldChange: (String, JsonElement) -> Unit,
    onSave: () -> Unit,
    onYamlTextChange: (String) -> Unit,
    onYamlSave: () -> Unit,
    onResetCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // ── Non-scrollable top section ──
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
        ) {
            // Path display
            state.path?.let { path ->
                Text(
                    text = "Path: $path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Mode toggle + search
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onModeToggle) {
                    Icon(
                        imageVector = if (state.yamlMode) Icons.Filled.Tune else Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        if (state.yamlMode) "Form" else "YAML",
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (!state.yamlMode) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = "Search settings…",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Top save bar — sticky, always visible when changes pending
            if (!state.yamlMode && state.modifiedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
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
                            text = "${state.modifiedKeys.size} change(s) pending",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = onSave,
                            enabled = !state.isSaving,
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.width(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // ── Scrollable content ──
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            if (state.yamlMode) {
                YAMLEditor(
                    yamlText = state.yamlText ?: "",
                    onYamlTextChange = onYamlTextChange,
                    isSaving = state.yamlIsSaving,
                    isLoading = state.yamlIsLoading,
                    onSave = onYamlSave,
                )
            } else {
                FormEditor(
                    schema = state.schema,
                    config = state.config,
                    defaults = state.defaults,
                    activeCategory = state.activeCategory,
                    searchQuery = state.searchQuery,
                    modifiedKeys = state.modifiedKeys,
                    isSaving = state.isSaving,
                    onCategoryChange = onCategoryChange,
                    onFieldChange = onFieldChange,
                    onSave = onSave,
                    onResetCategory = onResetCategory,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun YAMLEditor(
    yamlText: String,
    onYamlTextChange: (String) -> Unit,
    isSaving: Boolean,
    isLoading: Boolean,
    onSave: () -> Unit,
) {
    if (isLoading) {
        LoadingState()
        return
    }

    OutlinedTextField(
        value = yamlText,
        onValueChange = onYamlTextChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(400.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        maxLines = Int.MAX_VALUE,
    )

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isSaving,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Save,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(
                stringResource(R.string.config_action_save_yaml),
            )
        }
    }
}

@Composable
private fun FormEditor(
    schema: com.m57.hermescontrol.data.model.ConfigSchemaResponse?,
    config: Map<String, JsonElement>?,
    defaults: Map<String, JsonElement>?,
    activeCategory: String,
    searchQuery: String,
    modifiedKeys: Set<String>,
    isSaving: Boolean,
    onCategoryChange: (String) -> Unit,
    onFieldChange: (String, JsonElement) -> Unit,
    onSave: () -> Unit,
    onResetCategory: (String) -> Unit,
) {
    if (schema == null || config == null) return

    val isSearching = searchQuery.isNotBlank()
    val categoryCounts =
        remember(schema) {
            schema.fields.values.groupingBy { it.category ?: "general" }.eachCount()
        }

    // Compute which fields to show
    val visibleFields: List<Pair<String, SchemaField>> =
        remember(schema, activeCategory, searchQuery) {
            if (isSearching) {
                val query = searchQuery.lowercase()
                schema.fields.filter { (key, field) ->
                    val label = key.split(".").last().replace("_", " ")
                    key.lowercase().contains(query) ||
                        label.contains(query) ||
                        (field.description?.lowercase()?.contains(query) == true) ||
                        (field.category?.lowercase()?.contains(query) == true)
                }.entries.map { it.key to it.value }
            } else {
                schema.fields.filter { (_, field) ->
                    (field.category ?: "general") == activeCategory
                }.entries.map { it.key to it.value }
            }
        }

    // Show tabs only when not searching
    if (!isSearching) {
        ConfigTabs(
            categories = schema.category_order.filter { it in categoryCounts },
            categoryCounts = categoryCounts,
            selectedCategory = activeCategory,
            onCategorySelected = onCategoryChange,
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    // Non-schema config values — keys in the config that have zero schema coverage
    val nonSchemaKeys =
        remember(config, schema) {
            val schemaPaths = schema.fields.keys
            config.keys.filter { topKey ->
                schemaPaths.none { it == topKey || it.startsWith("$topKey.") }
            }.sorted()
        }

    // Fields list
    visibleFields.forEach { (key, field) ->
        ConfigField(
            key = key,
            field = field,
            currentValue = getJsonValue(config, key),
            isModified = key in modifiedKeys,
            onChange = { onFieldChange(key, it) },
        )
    }

    if (visibleFields.isEmpty()) {
        Text(
            text = if (isSearching) "No settings match your search." else "No fields in this category.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }

    // Render non-schema config keys as read-only cards
    if (nonSchemaKeys.isNotEmpty()) {
        Text(
            text = "Other settings",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        nonSchemaKeys.forEach { key ->
            val jsonText = config[key]?.toString() ?: ""
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = jsonText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Save + Reset buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = modifiedKeys.isNotEmpty() && !isSaving,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(stringResource(R.string.config_action_save))
            }
        }

        if (!isSearching) {
            OutlinedButton(
                onClick = { onResetCategory(activeCategory) },
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(stringResource(R.string.config_action_reset))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTabs(
    categories: List<String>,
    categoryCounts: Map<String, Int>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
        edgePadding = 0.dp,
    ) {
        categories.forEach { category ->
            val count = categoryCounts[category] ?: 0
            Tab(
                selected = category == selectedCategory,
                onClick = { onCategorySelected(category) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = category.replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Badge { Text(count.toString()) }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigField(
    key: String,
    field: SchemaField,
    currentValue: JsonElement?,
    isModified: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    val label = key.split(".").last().replace("_", " ").replaceFirstChar { it.uppercase() }
    val description = field.description ?: key.replace(".", " → ").replace("_", " ").replaceFirstChar { it.uppercase() }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isModified) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isModified) FontWeight.Bold else FontWeight.Medium,
            )
            if (!field.type.isNullOrEmpty() && field.type != "string") {
                Text(
                    text = field.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (field.type) {
                "boolean" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = currentValue?.asBoolean ?: false,
                            onCheckedChange = { onChange(JsonPrimitive(it)) },
                        )
                    }
                }

                "select" -> {
                    DropdownField(
                        label = label,
                        options = field.options ?: emptyList(),
                        selectedValue = currentValue?.asString ?: "",
                        onOptionSelected = { onChange(JsonPrimitive(it)) },
                    )
                    if (description != label) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                "number" -> {
                    OutlinedTextField(
                        value =
                            currentValue?.let {
                                if (it.isJsonPrimitive) it.asString else ""
                            } ?: "",
                        onValueChange = { text ->
                            text.toDoubleOrNull()?.let { doubleVal ->
                                if (doubleVal == doubleVal.toLong().toDouble()) {
                                    onChange(JsonPrimitive(doubleVal.toLong()))
                                } else {
                                    onChange(JsonPrimitive(doubleVal))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(description) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                }

                else -> {
                    // String or other type
                    val textValue =
                        currentValue?.let {
                            when {
                                it.isJsonPrimitive -> it.asString
                                it.isJsonObject -> it.toString()
                                it.isJsonArray -> it.toString()
                                else -> ""
                            }
                        } ?: ""

                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { onChange(JsonPrimitive(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(description) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selectedValue: String,
    onOptionSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedValue.ifEmpty { options.firstOrNull() ?: "" },
            onValueChange = {},
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Get a nested value from a flat config Map using a dot-path key. */
private fun getJsonValue(
    config: Map<String, JsonElement>,
    dotPath: String,
): JsonElement? {
    val parts = dotPath.split(".")
    var current: JsonElement? = null
    for (i in parts.indices) {
        val key = parts[i]
        if (i == 0) {
            current = config[key]
        } else {
            current = current?.asJsonObject?.get(key)
        }
        if (current == null) return null
    }
    return current
}
