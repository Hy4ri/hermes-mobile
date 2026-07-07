package com.m57.hermescontrol.ui.profiles.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.McpServerConfigInput
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.ui.profiles.ProfilesUiState
import com.m57.hermescontrol.ui.profiles.ProfilesViewModel

@Composable
fun ProfileBuilderView(
    state: ProfilesUiState,
    viewModel: ProfilesViewModel,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(1) }

    // Step 1: Identity
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    // Step 2: Model Config
    var selectedProvider by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("") }

    // Step 3: Skills
    var useDefaultSkills by remember { mutableStateOf(true) }
    var selectedSkills by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addedHubSkills by remember { mutableStateOf<List<String>>(emptyList()) }

    // Step 4: MCP Servers
    var mcpServers by remember { mutableStateOf<List<McpServerConfigInput>>(emptyList()) }

    val nameError = if (name.isNotBlank()) validateProfileName(name) else null

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Step Indicator / Stepper Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profiles_builder_step_indicator, step),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    when (step) {
                        1 -> stringResource(R.string.profiles_builder_step_identity)
                        2 -> stringResource(R.string.profiles_builder_step_model)
                        3 -> stringResource(R.string.profiles_builder_step_skills)
                        4 -> stringResource(R.string.profiles_builder_step_mcp)
                        else -> stringResource(R.string.profiles_builder_step_review)
                    },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        // Horizontal Progress indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 1..5) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (i <= step) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            if (state.isLoadingBuilderData) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (step) {
                    1 -> {
                        IdentityStep(
                            name = name,
                            onNameChange = { name = it },
                            nameError = nameError,
                            description = description,
                            onDescriptionChange = { description = it },
                        )
                    }

                    2 -> {
                        ModelStep(
                            providers = state.modelProviders,
                            selectedProvider = selectedProvider,
                            onProviderChange = {
                                selectedProvider = it
                                selectedModel = ""
                            },
                            selectedModel = selectedModel,
                            onModelChange = { selectedModel = it },
                        )
                    }

                    3 -> {
                        SkillsStep(
                            availableSkills = state.availableSkills,
                            useDefaultSkills = useDefaultSkills,
                            onUseDefaultSkillsChange = { useDefaultSkills = it },
                            selectedSkills = selectedSkills,
                            onSelectedSkillsChange = { selectedSkills = it },
                            addedHubSkills = addedHubSkills,
                            onAddedHubSkillsChange = { addedHubSkills = it },
                            hubSearchResults = state.hubSearchResults,
                            isSearchingHub = state.isSearchingHub,
                            onSearchHub = viewModel::searchHub,
                        )
                    }

                    4 -> {
                        McpStep(
                            mcpServers = mcpServers,
                            onMcpServersChange = { mcpServers = it },
                        )
                    }

                    5 -> {
                        ReviewStep(
                            name = name,
                            description = description,
                            provider = selectedProvider,
                            model = selectedModel,
                            useDefaultSkills = useDefaultSkills,
                            selectedSkills = selectedSkills,
                            addedHubSkills = addedHubSkills,
                            mcpServers = mcpServers,
                        )
                    }
                }
            }
        }

        // Stepper Navigation Footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (step > 1) {
                OutlinedButton(onClick = { step-- }) {
                    Text(stringResource(R.string.profiles_builder_action_back))
                }
            } else {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }

            if (step < 5) {
                Button(
                    onClick = { step++ },
                    enabled =
                        when (step) {
                            1 -> name.isNotBlank() && nameError == null
                            else -> true
                        },
                ) {
                    Text(stringResource(R.string.profiles_builder_action_next))
                }
            } else {
                Button(
                    onClick = {
                        val req =
                            CreateProfileRequest(
                                name = name,
                                description = description.ifBlank { null },
                                provider = selectedProvider.ifBlank { null },
                                model = selectedModel.ifBlank { null },
                                mcp_servers = mcpServers.ifEmpty { null },
                                keep_skills = if (useDefaultSkills) null else false,
                                hub_skills = addedHubSkills.ifEmpty { null },
                            )
                        viewModel.createProfile(req, onSuccess = onCancel)
                    },
                    enabled = !state.isLoading,
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.profiles_builder_action_create))
                    }
                }
            }
        }
    }
}

internal fun validateProfileName(name: String): String? {
    if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
        return "Only letters, numbers, hyphens, and underscores allowed"
    }
    return null
}

@Composable
private fun IdentityStep(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.profiles_builder_label_profile_name)) },
            placeholder = { Text("my-agent-profile") },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.profiles_builder_label_description)) },
            placeholder = { Text("Describe the purpose of this profile") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
    }
}

@Composable
private fun ModelStep(
    providers: List<ModelProvider>,
    selectedProvider: String,
    onProviderChange: (String) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.profiles_builder_title_provider),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            providers.forEach { provider ->
                val isSelected = selectedProvider == provider.slug
                OutlinedButton(
                    onClick = { onProviderChange(provider.slug) },
                    modifier = Modifier.weight(1f),
                    colors =
                        if (isSelected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                ) {
                    Text(provider.name.uppercase())
                }
            }
        }

        if (selectedProvider.isNotBlank()) {
            val providerObj = providers.find { it.slug == selectedProvider }
            providerObj?.models?.let { models ->
                Text(
                    text = stringResource(R.string.profiles_builder_title_model),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    models.forEach { modelName ->
                        val isSelected = selectedModel == modelName
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelChange(modelName) },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
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
                                Text(
                                    text = modelName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.secondary,
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

@Composable
private fun McpStep(
    mcpServers: List<McpServerConfigInput>,
    onMcpServersChange: (List<McpServerConfigInput>) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profiles_builder_mcp_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Button(onClick = { showAddDialog = true }) {
                Text(stringResource(R.string.profiles_builder_mcp_add))
            }
        }

        if (mcpServers.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.profiles_builder_mcp_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(mcpServers) { server ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Transport: ${server.transport.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (server.transport == "sse") {
                                    Text(
                                        text = "URL: ${server.url}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    Text(
                                        text = "Command: ${server.command}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onMcpServersChange(mcpServers - server) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove Server",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newServer ->
                onMcpServersChange(mcpServers + newServer)
                showAddDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (McpServerConfigInput) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("sse") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_builder_mcp_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_builder_mcp_name)) },
                    placeholder = { Text("postgres-mcp") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(
                    text = stringResource(R.string.profiles_builder_mcp_transport),
                    style = MaterialTheme.typography.labelMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { transport = "sse" },
                        modifier = Modifier.weight(1f),
                        colors =
                            if (transport == "sse") {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                    ) {
                        Text(stringResource(R.string.profiles_builder_mcp_transport_sse))
                    }

                    OutlinedButton(
                        onClick = { transport = "stdio" },
                        modifier = Modifier.weight(1f),
                        colors =
                            if (transport == "stdio") {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                    ) {
                        Text(stringResource(R.string.profiles_builder_mcp_transport_stdio))
                    }
                }

                if (transport == "sse") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.profiles_builder_mcp_sse_url)) },
                        placeholder = { Text("http://localhost:8000/sse") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.profiles_builder_mcp_command)) },
                        placeholder = { Text("npx") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = argsInput,
                        onValueChange = { argsInput = it },
                        label = { Text(stringResource(R.string.profiles_builder_mcp_args)) },
                        placeholder = {
                            Text("-y, @modelcontextprotocol/server-postgres")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val argsList =
                        if (argsInput.isNotBlank()) {
                            argsInput.split(",").map { it.trim() }
                        } else {
                            null
                        }
                    onAdd(
                        McpServerConfigInput(
                            name = name,
                            transport = transport,
                            url = if (transport == "sse") url else null,
                            command = if (transport == "stdio") command else null,
                            args = if (transport == "stdio") argsList else null,
                        ),
                    )
                },
                enabled =
                    name.isNotBlank() && (
                        (transport == "sse" && url.isNotBlank()) ||
                            (transport == "stdio" && command.isNotBlank())
                    ),
            ) {
                Text(stringResource(R.string.profiles_builder_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ReviewStep(
    name: String,
    description: String,
    provider: String,
    model: String,
    useDefaultSkills: Boolean,
    selectedSkills: Set<String>,
    addedHubSkills: List<String>,
    mcpServers: List<McpServerConfigInput>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.profiles_builder_review_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profiles_builder_review_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.profiles_builder_review_model_settings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider.isNotBlank() && model.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.profiles_builder_review_provider, provider),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.profiles_builder_review_model, model),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.profiles_builder_review_system_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.profiles_builder_review_skills_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (useDefaultSkills) {
                    Text(
                        text = stringResource(R.string.profiles_builder_review_skills_default),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.profiles_builder_review_skills_local, selectedSkills.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (addedHubSkills.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.profiles_builder_review_skills_hub, addedHubSkills.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.profiles_builder_review_mcp_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mcpServers.isNotEmpty()) {
                    mcpServers.forEach { server ->
                        Text(
                            text = "- ${server.name} (${server.transport.uppercase()})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.profiles_builder_review_mcp_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
