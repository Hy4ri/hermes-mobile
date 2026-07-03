package com.m57.hermescontrol.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.McpServerConfigInput
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ProfilesViewModel = viewModel { ProfilesViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var soulEditProfileName by remember { mutableStateOf<String?>(null) }
    var modelEditProfileName by remember { mutableStateOf<String?>(null) }
    var tempModelProvider by remember { mutableStateOf("") }
    var tempModelName by remember { mutableStateOf("") }

    var cloneProfileName by remember { mutableStateOf<String?>(null) }
    var newCloneName by remember { mutableStateOf("") }

    var descEditProfileName by remember { mutableStateOf<String?>(null) }
    var tempDescription by remember { mutableStateOf("") }

    var isBuildingProfile by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = {
            Text(
                if (isBuildingProfile) {
                    "Create Profile"
                } else {
                    stringResource(R.string.screen_profiles)
                },
            )
        },
        navigationIcon =
            if (isBuildingProfile) {
                NavIcon.Back { isBuildingProfile = false }
            } else {
                onOpenDrawer?.let { NavIcon.Menu(it) }
            },
        isRefreshing = if (isBuildingProfile) false else state.isLoading,
        onRefresh =
            if (isBuildingProfile) {
                null
            } else {
                { viewModel.loadProfiles() }
            },
        actions = {
            if (!isBuildingProfile) {
                IconButton(onClick = {
                    isBuildingProfile = true
                    viewModel.loadBuilderData()
                }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Profile",
                    )
                }
            }
        },
    ) { paddingValues ->
        if (isBuildingProfile) {
            ProfileBuilderView(
                state = state,
                viewModel = viewModel,
                onCancel = { isBuildingProfile = false },
            )
        } else {
            when {
                state.isLoading && state.profiles.isEmpty() -> {
                    LoadingState(modifier = Modifier.padding(paddingValues))
                }

                state.errorMessage != null -> {
                    ErrorState(
                        message = state.errorMessage ?: "",
                        onRetry = { viewModel.loadProfiles() },
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                state.profiles.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.profiles_empty_title),
                        subtitle = stringResource(R.string.profiles_empty_desc),
                        onAction = { viewModel.loadProfiles() },
                        actionLabel = stringResource(R.string.content_desc_refresh),
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                else -> {
                    Box(Modifier.fillMaxSize()) {
                        if (state.isLoading && state.profiles.isEmpty()) {
                            CircularProgressIndicator()
                        } else if (state.errorMessage != null && state.profiles.isEmpty()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(text = state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { viewModel.loadProfiles() }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(state.profiles, key = { it.name }) { profile ->
                                    val isActive = profile.name == state.activeProfileName
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            CardDefaults.cardColors(
                                                containerColor =
                                                    if (isActive) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                            ),
                                        onClick = {
                                            if (!isActive) {
                                                viewModel.selectActiveProfile(profile.name)
                                            }
                                        },
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = profile.name.replaceFirstChar { it.uppercase() },
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    val descriptionText =
                                                        if (!profile.description.isNullOrBlank()) {
                                                            profile.description
                                                        } else {
                                                            stringResource(R.string.profiles_description_placeholder)
                                                        }
                                                    val isPlaceholder = profile.description.isNullOrBlank()
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = descriptionText,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontStyle =
                                                            if (isPlaceholder) {
                                                                FontStyle.Italic
                                                            } else {
                                                                FontStyle.Normal
                                                            },
                                                        color =
                                                            if (isPlaceholder) {
                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                    alpha = 0.6f,
                                                                )
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                    )
                                                    if (profile.description_auto == true) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .clip(RoundedCornerShape(4.dp))
                                                                    .background(
                                                                        MaterialTheme.colorScheme.errorContainer,
                                                                    ).padding(horizontal = 6.dp, vertical = 2.dp),
                                                        ) {
                                                            Text(
                                                                text =
                                                                    stringResource(
                                                                        R.string.profiles_badge_auto_generated,
                                                                    ),
                                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                            )
                                                        }
                                                    }
                                                }
                                                if (isActive) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription =
                                                            stringResource(
                                                                R.string.profiles_content_desc_active,
                                                            ),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(start = 8.dp),
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text =
                                                            stringResource(
                                                                R.string.profiles_label_model,
                                                                profile.model ?: "None",
                                                            ),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                    Text(
                                                        text =
                                                            stringResource(
                                                                R.string.profiles_label_provider,
                                                                profile.provider ?: "None",
                                                            ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text =
                                                            stringResource(
                                                                R.string.profiles_label_skills,
                                                                profile.skill_count ?: 0,
                                                            ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    profile.path?.let {
                                                        Text(
                                                            text = stringResource(R.string.profiles_label_path, it),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color =
                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                    alpha = 0.8f,
                                                                ),
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(16.dp))

                                            if (!isActive) {
                                                Button(
                                                    onClick = { viewModel.selectActiveProfile(profile.name) },
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 8.dp),
                                                ) {
                                                    Text(stringResource(R.string.profiles_action_activate))
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        soulEditProfileName = profile.name
                                                        viewModel.loadSoul(profile.name)
                                                    },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Edit,
                                                        contentDescription = null,
                                                        modifier = Modifier.width(16.dp),
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(stringResource(R.string.profiles_action_edit_soul))
                                                }

                                                Button(
                                                    onClick = {
                                                        modelEditProfileName = profile.name
                                                        tempModelProvider = profile.provider ?: ""
                                                        tempModelName = profile.model ?: ""
                                                    },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                ) {
                                                    Text(stringResource(R.string.profiles_action_set_model))
                                                }

                                                var showMenu by remember { mutableStateOf(false) }

                                                Box {
                                                    IconButton(onClick = { showMenu = true }) {
                                                        Icon(
                                                            imageVector = Icons.Default.MoreVert,
                                                            contentDescription = "More options",
                                                        )
                                                    }

                                                    DropdownMenu(
                                                        expanded = showMenu,
                                                        onDismissRequest = { showMenu = false },
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    stringResource(
                                                                        R.string.profiles_action_edit_description,
                                                                    ),
                                                                )
                                                            },
                                                            onClick = {
                                                                showMenu = false
                                                                descEditProfileName = profile.name
                                                                tempDescription = profile.description ?: ""
                                                            },
                                                        )
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    stringResource(R.string.profiles_action_clone),
                                                                )
                                                            },
                                                            onClick = {
                                                                showMenu = false
                                                                cloneProfileName = profile.name
                                                                newCloneName = ""
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
    }

    if (soulEditProfileName != null) {
        val initialText = state.selectedSoulContent ?: ""
        var soulText by remember(initialText) { mutableStateOf(initialText) }

        AlertDialog(
            onDismissRequest = {
                soulEditProfileName = null
                viewModel.closeSoulDialog()
            },
            title = {
                Text(
                    text =
                        stringResource(
                            R.string.profiles_title_edit_soul,
                            soulEditProfileName.orEmpty(),
                        ),
                )
            },
            text = {
                if (state.isLoadingSoul) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    OutlinedTextField(
                        value = soulText,
                        onValueChange = { soulText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val profileName = soulEditProfileName
                        if (profileName != null) {
                            viewModel.saveSoul(profileName, soulText)
                        }
                        soulEditProfileName = null
                    },
                    enabled = !state.isLoadingSoul,
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        soulEditProfileName = null
                        viewModel.closeSoulDialog()
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (modelEditProfileName != null) {
        AlertDialog(
            onDismissRequest = { modelEditProfileName = null },
            title = {
                Text(
                    text =
                        stringResource(
                            R.string.profiles_title_set_model,
                            modelEditProfileName.orEmpty(),
                        ),
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = tempModelProvider,
                        onValueChange = { tempModelProvider = it },
                        label = { Text(stringResource(R.string.profiles_label_provider_input)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = tempModelName,
                        onValueChange = { tempModelName = it },
                        label = { Text(stringResource(R.string.profiles_label_model_input)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val profileName = modelEditProfileName
                        if (profileName != null) {
                            viewModel.updateModel(profileName, tempModelProvider, tempModelName)
                        }
                        modelEditProfileName = null
                    },
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { modelEditProfileName = null },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (cloneProfileName != null) {
        AlertDialog(
            onDismissRequest = { cloneProfileName = null },
            title = {
                Text(
                    text =
                        stringResource(
                            R.string.profiles_title_clone,
                            cloneProfileName.orEmpty(),
                        ),
                )
            },
            text = {
                OutlinedTextField(
                    value = newCloneName,
                    onValueChange = { newCloneName = it },
                    label = { Text(stringResource(R.string.profiles_label_new_name_input)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sourceName = cloneProfileName
                        if (sourceName != null && newCloneName.isNotBlank()) {
                            viewModel.cloneProfile(sourceName, newCloneName)
                        }
                        cloneProfileName = null
                    },
                    enabled = newCloneName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.profiles_action_clone))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { cloneProfileName = null },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (descEditProfileName != null) {
        AlertDialog(
            onDismissRequest = { descEditProfileName = null },
            title = {
                Text(
                    text =
                        stringResource(
                            R.string.profiles_title_edit_description,
                            descEditProfileName.orEmpty(),
                        ),
                )
            },
            text = {
                OutlinedTextField(
                    value = tempDescription,
                    onValueChange = { tempDescription = it },
                    label = { Text(stringResource(R.string.profiles_label_description_input)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val profileName = descEditProfileName
                        if (profileName != null) {
                            viewModel.updateProfileDescription(profileName, tempDescription)
                        }
                        descEditProfileName = null
                    },
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { descEditProfileName = null },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------
// Profile Builder Wizard
// ---------------------------------------------------------------------

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
                text = "Step $step of 5",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    when (step) {
                        1 -> "Identity"
                        2 -> "Model Configuration"
                        3 -> "Skills selection"
                        4 -> "MCP Servers"
                        else -> "Review & Create"
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
                    Text("Back")
                }
            } else {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
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
                    Text("Next")
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
                        Text("Create Profile")
                    }
                }
            }
        }
    }
}

private fun validateProfileName(name: String): String? {
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
            label = { Text("Profile Name") },
            placeholder = { Text("my-agent-profile") },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description") },
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
            text = "Select Provider (Optional)",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            providers.forEach { provider ->
                val isSelected = selectedProvider == provider.name
                OutlinedButton(
                    onClick = { onProviderChange(provider.name) },
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
            val providerObj = providers.find { it.name == selectedProvider }
            providerObj?.models?.let { models ->
                Text(
                    text = "Select Model",
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
private fun SkillsStep(
    availableSkills: List<Skill>,
    useDefaultSkills: Boolean,
    onUseDefaultSkillsChange: (Boolean) -> Unit,
    selectedSkills: Set<String>,
    onSelectedSkillsChange: (Set<String>) -> Unit,
    addedHubSkills: List<String>,
    onAddedHubSkillsChange: (List<String>) -> Unit,
    hubSearchResults: List<HubSkill>,
    isSearchingHub: Boolean,
    onSearchHub: (String) -> Unit,
) {
    var hubSearchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = useDefaultSkills,
                onCheckedChange = onUseDefaultSkillsChange,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Include default skills bundle",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Automatically bundles common system tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        if (!useDefaultSkills) {
            Text(
                text = "Select Local Skills",
                style = MaterialTheme.typography.titleSmall,
            )
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableSkills) { skill ->
                    val isChecked = selectedSkills.contains(skill.name)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        onSelectedSkillsChange(selectedSkills - skill.name)
                                    } else {
                                        onSelectedSkillsChange(selectedSkills + skill.name)
                                    }
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked == true) {
                                    onSelectedSkillsChange(selectedSkills + skill.name)
                                } else {
                                    onSelectedSkillsChange(selectedSkills - skill.name)
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            skill.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Browse and add Skills from Hermes Hub
            Text(
                text = "Add Skills from Hermes Hub",
                style = MaterialTheme.typography.titleSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hubSearchQuery,
                    onValueChange = { hubSearchQuery = it },
                    label = { Text("Search Hub") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onSearchHub(hubSearchQuery) },
                    enabled = hubSearchQuery.isNotBlank() && !isSearchingHub,
                ) {
                    if (isSearchingHub) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    }
                }
            }

            if (addedHubSkills.isNotEmpty()) {
                Text(
                    text = "Added Skills:",
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(
                    modifier =
                        Modifier
                            .height(80.dp)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(addedHubSkills) { skillName ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(4.dp),
                                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = skillName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = { onAddedHubSkillsChange(addedHubSkills - skillName) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (hubSearchResults.isNotEmpty()) {
                Text(
                    text = "Search Results:",
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hubSearchResults) { skill ->
                        val isAdded = addedHubSkills.contains(skill.name)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                skill.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (isAdded) {
                                        onAddedHubSkillsChange(addedHubSkills - skill.name)
                                    } else {
                                        onAddedHubSkillsChange(addedHubSkills + skill.name)
                                    }
                                },
                                colors =
                                    if (isAdded) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                            ) {
                                if (isAdded) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Text("Add")
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
                text = "Configured MCP Servers",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(onClick = { showAddDialog = true }) {
                Text("Add Server")
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
                    text = "No MCP servers configured yet",
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
        title = { Text("Add MCP Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("postgres-mcp") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(
                    text = "Transport Protocol",
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
                        Text("SSE (HTTP)")
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
                        Text("Stdio")
                    }
                }

                if (transport == "sse") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("SSE URL") },
                        placeholder = { Text("http://localhost:8000/sse") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("npx") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = argsInput,
                        onValueChange = { argsInput = it },
                        label = { Text("Arguments (comma separated)") },
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
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
                    text = "Profile Name",
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
                        text = "Description",
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
                    text = "Model Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider.isNotBlank() && model.isNotBlank()) {
                    Text(
                        text = "Provider: $provider",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Model: $model",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "Using system defaults",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Skills Configuration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (useDefaultSkills) {
                    Text(
                        text = "Full Default Bundle",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "${selectedSkills.size} local skills selected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (addedHubSkills.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${addedHubSkills.size} hub skills added",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "MCP Servers",
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
                        text = "No MCP servers configured",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
