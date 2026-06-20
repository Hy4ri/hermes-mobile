package com.m57.hermescontrol.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    HermesScaffold(
        title = { Text("Settings") },
        showBack = true,
        onBack = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connection section
            SectionCard(title = "Connection") {
                // Connection Profiles management in Settings
                if (state.profiles.isNotEmpty()) {
                    var profilesExpanded by remember { mutableStateOf(false) }
                    Text(
                        text = "Connection Profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = { profilesExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val activeProfile = state.profiles.firstOrNull { it.id == state.selectedProfileId }
                            Text(text = activeProfile?.name ?: "Standalone / Default")
                        }
                        DropdownMenu(
                            expanded = profilesExpanded,
                            onDismissRequest = { profilesExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f),
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Standalone / Default)") },
                                onClick = {
                                    viewModel.selectProfile(null)
                                    profilesExpanded = false
                                },
                            )
                            state.profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(profile.name)
                                            IconButton(
                                                onClick = {
                                                    viewModel.deleteProfile(profile.id)
                                                    profilesExpanded = false
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Delete Profile",
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectProfile(profile.id)
                                        profilesExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (state.selectedProfileId != null) {
                        OutlinedTextField(
                            value = state.renameProfileName,
                            onValueChange = viewModel::onRenameProfileNameChange,
                            label = { Text("Rename Profile") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Button(
                                    onClick = viewModel::renameProfile,
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Text("Rename")
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text("Auth Token") },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector =
                                    if (passwordVisible) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                contentDescription =
                                    if (passwordVisible) {
                                        "Hide token"
                                    } else {
                                        "Show token"
                                    },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // B7 (Jun 18 2026): one-tap escape hatch for when the stored
                // token is corrupted / rejected. Clears the token field and
                // persists the empty value immediately so a stress-free
                // re-pair is possible without manually selecting-and-deleting
                // the masked password field character by character.
                OutlinedButton(
                    onClick = {
                        viewModel.onTokenChange("")
                        viewModel.save()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear Token")
                }
            }

            // Behavior section
            SectionCard(title = "Behavior") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Auto-Reconnect",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Automatically reconnect on disconnect",
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                    Switch(
                        checked = state.autoReconnect,
                        onCheckedChange = viewModel::onAutoReconnectChange,
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    )
                }
            }

            // Appearance section
            SectionCard(title = "Appearance") {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemePreference.entries.forEachIndexed { index, pref ->
                        SegmentedButton(
                            selected = state.themePreference == pref,
                            onClick = { viewModel.onThemeChange(pref) },
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemePreference.entries.size,
                                ),
                        ) {
                            Text(
                                pref.name
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() },
                            )
                        }
                    }
                }
            }

            // Chat section
            SectionCard(title = "Chat") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Typing Effect",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Animate new messages word by word",
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                    Switch(
                        checked = state.typingEffectEnabled,
                        onCheckedChange = viewModel::onTypingEffectEnabledChange,
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    )
                }

                // Delay slider — only visible when effect is enabled
                AnimatedVisibility(visible = state.typingEffectEnabled) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Delay: ${state.typingEffectDelayMs} ms",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = state.typingEffectDelayMs.toFloat(),
                            onValueChange = { viewModel.onTypingEffectDelayMsChange(it.toInt()) },
                            valueRange = 10f..100f,
                            steps = 8, // 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "10 ms",
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                            )
                            Text(
                                text = "100 ms",
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                            )
                        }
                    }
                }
            }

            // Navigation Bar section
            SectionCard(title = "Navigation Bar") {
                Text(
                    text = "Bottom bar items (${state.selectedNavItems.size}/5)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                state.selectedNavItems.forEachIndexed { index, name ->
                    val label = viewModel.availableNavItems.firstOrNull { it.first == name }?.second ?: name
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    val list = state.selectedNavItems.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index - 1]
                                    list[index - 1] = temp
                                    viewModel.reorderNavItems(list)
                                },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = {
                                    val list = state.selectedNavItems.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index + 1]
                                    list[index + 1] = temp
                                    viewModel.reorderNavItems(list)
                                },
                                enabled = index < state.selectedNavItems.lastIndex,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                            }
                            IconButton(onClick = { viewModel.removeNavItem(name) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $label")
                            }
                        }
                    }
                }

                if (state.selectedNavItems.size < 5) {
                    val available = viewModel.availableNavItems.filter { it.first !in state.selectedNavItems }
                    if (available.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.size(4.dp))
                                Text("Add Item")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                available.forEach { (name, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.addNavItem(name)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Maximum of 5 items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Test result
            AnimatedVisibility(
                visible = state.testResult != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                state.testResult?.let { result ->
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (result.startsWith("✅")) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = result,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Save indicator
            AnimatedVisibility(
                visible = state.isSaved,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = "✅ Settings saved",
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                )
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Test Connection")
                    }
                }

                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── About section ─────────────────────────────────────────
            SectionCard(title = "About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡ Hermes Control",
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                InfoRow(label = "Version", value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(
                    label = "Build",
                    value = if (BuildConfig.DEBUG) "Debug" else "Release",
                )
                if (BuildConfig.GIT_SHA.isNotBlank()) {
                    InfoRow(label = "Commit", value = BuildConfig.GIT_SHA)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "https://github.com/Hy4ri/hermes-mobile",
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}
