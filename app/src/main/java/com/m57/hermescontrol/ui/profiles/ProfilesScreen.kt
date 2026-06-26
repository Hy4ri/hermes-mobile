package com.m57.hermescontrol.ui.profiles

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SpinningIndicator
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

    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_profiles)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadProfiles() },
    ) { paddingValues ->
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
                        SpinningIndicator()
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
                                                profile.description?.let {
                                                    if (it.isNotBlank()) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = it,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            Column {
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
                                            ) {
                                                Text(stringResource(R.string.profiles_action_set_model))
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
                        SpinningIndicator()
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
}
