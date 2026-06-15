package com.m57.hermescontrol.ui.profiles

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ProfilesViewModel = viewModel { ProfilesViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var soulEditProfileName by remember { mutableStateOf<String?>(null) }
    var modelEditProfileName by remember { mutableStateOf<String?>(null) }
    var tempModelProvider by remember { mutableStateOf("") }
    var tempModelName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open Drawer",
                            )
                        }
                    }
                },
                title = { Text("Agent Profiles") },
                actions = {
                    IconButton(onClick = { viewModel.loadProfiles() }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            if (state.isLoading && state.profiles.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.errorMessage != null && state.profiles.isEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadProfiles() }) {
                        Text("Retry")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.profiles) { profile ->
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
                                            contentDescription = "Active Profile",
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
                                            text = "Model: ${profile.model ?: "None"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = "Provider: ${profile.provider ?: "None"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

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
                                        Text("Edit Soul")
                                    }

                                    Button(
                                        onClick = {
                                            modelEditProfileName = profile.name
                                            tempModelProvider = profile.provider ?: ""
                                            tempModelName = profile.model ?: ""
                                        },
                                    ) {
                                        Text("Set Model")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Soul Edit Dialog
        soulEditProfileName?.let { profileName ->
            var soulText by remember(state.selectedSoulContent) {
                mutableStateOf(state.selectedSoulContent ?: "")
            }

            AlertDialog(
                onDismissRequest = {
                    soulEditProfileName = null
                    viewModel.closeSoulDialog()
                },
                title = { Text("Edit Soul ($profileName)") },
                text = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoadingSoul) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            OutlinedTextField(
                                value = soulText,
                                onValueChange = { soulText = it },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                label = { Text("Soul/Persona Prompts") },
                                maxLines = 10,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.saveSoul(profileName, soulText)
                            soulEditProfileName = null
                        },
                        enabled = !state.isLoadingSoul,
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            soulEditProfileName = null
                            viewModel.closeSoulDialog()
                        },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }

        // Model Edit Dialog
        modelEditProfileName?.let { profileName ->
            AlertDialog(
                onDismissRequest = { modelEditProfileName = null },
                title = { Text("Switch Model ($profileName)") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = tempModelProvider,
                            onValueChange = { tempModelProvider = it },
                            label = { Text("Provider (e.g. nvidia, openai, local)") },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = tempModelName,
                            onValueChange = { tempModelName = it },
                            label = { Text("Model Name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.updateModel(profileName, tempModelProvider, tempModelName)
                            modelEditProfileName = null
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { modelEditProfileName = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
