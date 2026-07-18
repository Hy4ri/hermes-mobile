package com.m57.hermescontrol.ui.profiles

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.profiles.components.ProfileBuilderView
import com.m57.hermescontrol.ui.profiles.components.validateProfileName

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
                    SkeletonListState(modifier = Modifier.padding(paddingValues))
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
                val errorMsg = if (newCloneName.isNotEmpty()) validateProfileName(newCloneName) else null
                OutlinedTextField(
                    value = newCloneName,
                    onValueChange = { newCloneName = it },
                    label = { Text(stringResource(R.string.profiles_label_new_name_input)) },
                    isError = errorMsg != null,
                    supportingText = errorMsg?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sourceName = cloneProfileName
                        if (sourceName != null && newCloneName.isNotBlank() &&
                            validateProfileName(newCloneName) == null
                        ) {
                            viewModel.cloneProfile(sourceName, newCloneName)
                        }
                        cloneProfileName = null
                    },
                    enabled = newCloneName.isNotBlank() && validateProfileName(newCloneName) == null,
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
