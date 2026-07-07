package com.m57.hermescontrol.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon

enum class SettingsTab {
    CONNECTION,
    APPEARANCE,
    BEHAVIOR,
    ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.navigateToLogin) {
        if (state.navigateToLogin) {
            onNavigateToLogin()
            viewModel.clearNavigateToLogin()
        }
    }

    var passwordVisible by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.CONNECTION) }

    BackHandler(onBack = onBack)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_settings)) },
        navigationIcon = NavIcon.Back(onBack),
        actions = {
            TextButton(onClick = viewModel::save) {
                Text(stringResource(R.string.action_save))
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
            ) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                when (tab) {
                                    SettingsTab.CONNECTION -> stringResource(R.string.settings_tab_connection)
                                    SettingsTab.APPEARANCE -> stringResource(R.string.settings_tab_appearance)
                                    SettingsTab.BEHAVIOR -> stringResource(R.string.settings_tab_behavior)
                                    SettingsTab.ABOUT -> stringResource(R.string.settings_tab_about)
                                },
                            )
                        },
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    SettingsTab.CONNECTION -> {
                        ConnectionSection(
                            state = state,
                            viewModel = viewModel,
                            passwordVisible = passwordVisible,
                            onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        )

                        TestResultCard(testResult = state.testResult)
                        SaveIndicator(isSaved = state.isSaved)
                        TestConnectionButton(
                            isTesting = state.isTesting,
                            onTest = viewModel::testConnection,
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    SettingsTab.BEHAVIOR -> {
                        BehaviorSection(
                            autoReconnect = state.autoReconnect,
                            onAutoReconnectChange = viewModel::onAutoReconnectChange,
                        )
                    }

                    SettingsTab.APPEARANCE -> {
                        AppearanceSection(
                            themePreference = state.themePreference,
                            onThemeChange = viewModel::onThemeChange,
                            useDynamicColors = state.useDynamicColors,
                            onUseDynamicColorsChange = viewModel::onUseDynamicColorsChange,
                            themePreset = state.themePreset,
                            onThemePresetChange = viewModel::onThemePresetChange,
                        )

                        ChatSection(
                            typingEffectEnabled = state.typingEffectEnabled,
                            onTypingEffectEnabledChange = viewModel::onTypingEffectEnabledChange,
                            typingEffectDelayMs = state.typingEffectDelayMs,
                            onTypingEffectDelayMsChange = viewModel::onTypingEffectDelayMsChange,
                        )

                        NavBarSection(
                            bottomNavDisplayMode = state.bottomNavDisplayMode,
                            onBottomNavDisplayModeChange = viewModel::onBottomNavDisplayModeChange,
                            selectedNavItems = state.selectedNavItems,
                            availableNavItems = viewModel.availableNavItems,
                            onReorderNavItems = viewModel::reorderNavItems,
                            onRemoveNavItem = viewModel::removeNavItem,
                            onAddNavItem = viewModel::addNavItem,
                        )
                    }

                    SettingsTab.ABOUT -> {
                        // ── About section ─────────────────────────────────────────
                        SectionCard(title = stringResource(R.string.settings_sec_about)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.settings_about_app_name),
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(8.dp))

                            InfoRow(
                                label = stringResource(R.string.settings_about_version),
                                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            )
                            InfoRow(
                                label = stringResource(R.string.settings_about_build),
                                value =
                                    if (BuildConfig.DEBUG) {
                                        stringResource(R.string.settings_about_debug)
                                    } else {
                                        stringResource(R.string.settings_about_release)
                                    },
                            )
                            if (BuildConfig.GIT_SHA.isNotBlank()) {
                                InfoRow(
                                    label = stringResource(R.string.settings_about_commit),
                                    value = BuildConfig.GIT_SHA,
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "https://github.com/Hy4ri/hermes-mobile",
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                            )

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    viewModel.logout()
                                    onLogout()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                            ) {
                                Text(stringResource(R.string.settings_logout))
                            }
                        }
                    }
                }
            }
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

@Composable
private fun ConnectionSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_connection)) {
        if (state.profiles.isNotEmpty()) {
            // ── Saved profiles list ──────────────────────────────────
            Text(
                text = stringResource(R.string.settings_saved_profiles, state.profiles.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            state.profiles.forEach { profile ->
                val isActive = profile.id == state.selectedProfileId
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { viewModel.selectProfile(profile.id) },
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                },
                        ),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            )
                            Text(
                                text = "${profile.host}:${profile.port}",
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            IconButton(
                                onClick = { viewModel.openEditProfile(profile.id) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.settings_action_edit_profile),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = { viewModel.requestDeleteProfile(profile.id) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.content_desc_delete_profile),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Add profile button ───────────────────────────────────────
        OutlinedButton(
            onClick = viewModel::openAddProfile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(stringResource(R.string.settings_action_add_profile))
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.host,
            onValueChange = viewModel::onHostChange,
            label = { Text(stringResource(R.string.settings_field_host)) },
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
            label = { Text(stringResource(R.string.settings_field_port)) },
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
            label = { Text(stringResource(R.string.settings_field_token)) },
            singleLine = true,
            visualTransformation =
                if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector =
                            if (passwordVisible) {
                                Icons.Filled.Visibility
                            } else {
                                Icons.Filled.VisibilityOff
                            },
                        contentDescription =
                            if (passwordVisible) {
                                stringResource(R.string.content_desc_hide_token)
                            } else {
                                stringResource(R.string.content_desc_show_token)
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
            Text(stringResource(R.string.settings_action_clear_token))
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (state.showProfileDialog) {
        ProfileEditorDialog(
            isEditing = state.editingProfileId != null,
            name = state.dialogProfileName,
            host = state.dialogProfileHost,
            port = state.dialogProfilePort,
            token = state.dialogProfileToken,
            onNameChange = viewModel::onDialogProfileNameChange,
            onHostChange = viewModel::onDialogProfileHostChange,
            onPortChange = viewModel::onDialogProfilePortChange,
            onTokenChange = viewModel::onDialogProfileTokenChange,
            onSave = viewModel::saveProfileFromDialog,
            onDismiss = viewModel::closeProfileDialog,
        )
    }

    if (state.showDeleteConfirm) {
        DeleteProfileConfirmDialog(
            profileName = state.profileToDeleteName,
            onConfirm = viewModel::confirmDeleteProfile,
            onDismiss = viewModel::cancelDeleteProfile,
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    isEditing: Boolean,
    name: String,
    host: String,
    port: String,
    token: String,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        if (isEditing) {
            stringResource(R.string.settings_title_edit_profile)
        } else {
            stringResource(R.string.settings_title_add_profile)
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.settings_field_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text(stringResource(R.string.settings_field_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.settings_field_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isEditing) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        label = { Text(stringResource(R.string.settings_field_token)) },
                        singleLine = true,
                        visualTransformation =
                            if (token.isNotEmpty()) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = name.isNotBlank() && host.isNotBlank()) {
                Text(stringResource(R.string.action_save))
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
private fun DeleteProfileConfirmDialog(
    profileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = {
            Text(stringResource(R.string.settings_delete_confirm_message, profileName))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_delete))
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
private fun TestResultCard(testResult: String?) {
    AnimatedVisibility(
        visible = testResult != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        testResult?.let { result ->
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
}

@Composable
private fun SaveIndicator(isSaved: Boolean) {
    AnimatedVisibility(
        visible = isSaved,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Text(
            text = stringResource(R.string.settings_save_success),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@Composable
private fun TestConnectionButton(
    isTesting: Boolean,
    onTest: () -> Unit,
) {
    OutlinedButton(
        onClick = onTest,
        enabled = !isTesting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.settings_action_test_connection))
        }
    }
}

@Composable
private fun BehaviorSection(
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_behavior)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_item_auto_reconnect),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_auto_reconnect),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = autoReconnect,
                onCheckedChange = onAutoReconnectChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit,
    themePreset: ThemePreset,
    onThemePresetChange: (ThemePreset) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_appearance)) {
        Text(
            text = stringResource(R.string.settings_item_theme),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemePreference.entries.forEachIndexed { index, pref ->
                SegmentedButton(
                    selected = themePreference == pref,
                    onClick = { onThemeChange(pref) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemePreference.entries.size,
                        ),
                ) {
                    Text(
                        when (pref) {
                            ThemePreference.SYSTEM -> stringResource(R.string.theme_system)
                            ThemePreference.LIGHT -> stringResource(R.string.theme_light)
                            ThemePreference.DARK -> stringResource(R.string.theme_dark)
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_use_dynamic_colors),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_use_dynamic_colors),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = useDynamicColors,
                onCheckedChange = onUseDynamicColorsChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_item_theme_preset),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        var presetsExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { presetsExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (themePreset) {
                        ThemePreset.DEFAULT -> stringResource(R.string.theme_preset_default)
                        ThemePreset.MONOCHROME -> stringResource(R.string.theme_preset_monochrome)
                        ThemePreset.GRUVBOX -> stringResource(R.string.theme_preset_gruvbox)
                        ThemePreset.CATPPUCCIN -> stringResource(R.string.theme_preset_catppuccin)
                        ThemePreset.AMOLED -> stringResource(R.string.theme_preset_amoled)
                        ThemePreset.NEON_NOIR -> stringResource(R.string.theme_preset_neon_noir)
                    },
                )
            }
            DropdownMenu(
                expanded = presetsExpanded,
                onDismissRequest = { presetsExpanded = false },
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                ThemePreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (preset) {
                                    ThemePreset.DEFAULT -> {
                                        stringResource(
                                            R.string.theme_preset_default,
                                        )
                                    }

                                    ThemePreset.MONOCHROME -> {
                                        stringResource(
                                            R.string.theme_preset_monochrome,
                                        )
                                    }

                                    ThemePreset.GRUVBOX -> {
                                        stringResource(
                                            R.string.theme_preset_gruvbox,
                                        )
                                    }

                                    ThemePreset.CATPPUCCIN -> {
                                        stringResource(
                                            R.string.theme_preset_catppuccin,
                                        )
                                    }

                                    ThemePreset.AMOLED -> {
                                        stringResource(
                                            R.string.theme_preset_amoled,
                                        )
                                    }

                                    ThemePreset.NEON_NOIR -> {
                                        stringResource(
                                            R.string.theme_preset_neon_noir,
                                        )
                                    }
                                },
                            )
                        },
                        onClick = {
                            onThemePresetChange(preset)
                            presetsExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSection(
    typingEffectEnabled: Boolean,
    onTypingEffectEnabledChange: (Boolean) -> Unit,
    typingEffectDelayMs: Int,
    onTypingEffectDelayMsChange: (Int) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_chat)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_item_typing_effect),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_typing_effect),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = typingEffectEnabled,
                onCheckedChange = onTypingEffectEnabledChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }

        // Delay slider — only visible when effect is enabled
        AnimatedVisibility(visible = typingEffectEnabled) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text =
                        stringResource(
                            R.string.settings_item_typing_delay,
                            typingEffectDelayMs,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = typingEffectDelayMs.toFloat(),
                    onValueChange = { onTypingEffectDelayMsChange(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 8, // 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_delay_10ms),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                    Text(
                        text = stringResource(R.string.settings_delay_100ms),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavBarSection(
    bottomNavDisplayMode: BottomNavDisplayMode,
    onBottomNavDisplayModeChange: (BottomNavDisplayMode) -> Unit,
    selectedNavItems: List<String>,
    availableNavItems: List<Pair<String, Int>>,
    onReorderNavItems: (List<String>) -> Unit,
    onRemoveNavItem: (String) -> Unit,
    onAddNavItem: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_nav_bar)) {
        Text(
            text = stringResource(R.string.settings_item_bottom_nav_display_mode),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BottomNavDisplayMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = bottomNavDisplayMode == mode,
                    onClick = { onBottomNavDisplayModeChange(mode) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = BottomNavDisplayMode.entries.size,
                        ),
                ) {
                    Text(
                        when (mode) {
                            BottomNavDisplayMode.ICON_AND_TEXT -> {
                                stringResource(
                                    R.string.settings_display_mode_icon_and_text,
                                )
                            }

                            BottomNavDisplayMode.ICON_ONLY -> {
                                stringResource(
                                    R.string.settings_display_mode_icon_only,
                                )
                            }

                            BottomNavDisplayMode.TEXT_ONLY -> {
                                stringResource(
                                    R.string.settings_display_mode_text_only,
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_item_nav_items, selectedNavItems.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        selectedNavItems.forEachIndexed { index, name ->
            val labelRes = availableNavItems.firstOrNull { it.first == name }?.second
            val label = if (labelRes != null) stringResource(labelRes) else name
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
                            val list = selectedNavItems.toMutableList()
                            val temp = list[index]
                            list[index] = list[index - 1]
                            list[index - 1] = temp
                            onReorderNavItems(list)
                        },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.content_desc_move_up),
                        )
                    }
                    IconButton(
                        onClick = {
                            val list = selectedNavItems.toMutableList()
                            val temp = list[index]
                            list[index] = list[index + 1]
                            list[index + 1] = temp
                            onReorderNavItems(list)
                        },
                        enabled = index < selectedNavItems.lastIndex,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.content_desc_move_down),
                        )
                    }
                    IconButton(onClick = { onRemoveNavItem(name) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription =
                                stringResource(
                                    R.string.content_desc_remove_item,
                                    label,
                                ),
                        )
                    }
                }
            }
        }

        if (selectedNavItems.size < 5) {
            val available =
                availableNavItems.filter { it.first !in selectedNavItems }
            if (available.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.settings_action_add_item))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        available.forEach { (name, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = {
                                    onAddNavItem(name)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_nav_max_reached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutSection(onLogout: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_sec_about)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_about_app_name),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        InfoRow(
            label = stringResource(R.string.settings_about_version),
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        InfoRow(
            label = stringResource(R.string.settings_about_build),
            value =
                if (BuildConfig.DEBUG) {
                    stringResource(R.string.settings_about_debug)
                } else {
                    stringResource(R.string.settings_about_release)
                },
        )
        if (BuildConfig.GIT_SHA.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.settings_about_commit),
                value = BuildConfig.GIT_SHA,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "https://github.com/Hy4ri/hermes-mobile",
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Text(stringResource(R.string.settings_logout))
        }
    }
}
