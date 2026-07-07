package com.m57.hermescontrol.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.ui.settings.SettingsUiState
import com.m57.hermescontrol.ui.settings.SettingsViewModel

@Composable
internal fun ConnectionSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_connection)) {
        // ── Saved profiles list ──────────────────────────────────
        if (state.profiles.isNotEmpty()) {
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
                            .clickable(role = Role.Button) { viewModel.selectProfile(profile.id) },
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
internal fun TestResultCard(testResult: String?) {
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
internal fun SaveIndicator(isSaved: Boolean) {
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
internal fun TestConnectionButton(
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
