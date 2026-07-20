package com.m57.hermescontrol.ui.connect

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel =
        viewModel(
            factory =
                run {
                    val app = LocalContext.current.applicationContext as Application
                    ConnectViewModelFactory(app)
                },
        ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.connectionSuccess) {
        if (state.connectionSuccess) {
            onConnected()
        }
    }

    var passwordVisible by remember { mutableStateOf(false) }
    var showManualFields by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hero brand
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }

            Text(
                text = stringResource(R.string.notif_title),
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
            )
            Text(
                text = stringResource(R.string.nav_drawer_subtitle),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Connection Profiles Dropdown / Selection
            if (state.profiles.isNotEmpty()) {
                var profilesExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { profilesExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text =
                                state.selectedProfile?.let { p ->
                                    stringResource(R.string.connect_selected_profile, p.name)
                                } ?: stringResource(R.string.connect_action_select_profile),
                        )
                    }
                    DropdownMenu(
                        expanded = profilesExpanded,
                        onDismissRequest = { profilesExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f),
                    ) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
                                onClick = {
                                    viewModel.selectProfile(profile)
                                    profilesExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Pairing (primary path)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Error message
                AnimatedVisibility(
                    visible = state.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    state.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Profile Save Options prior to connecting
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = state.saveProfile,
                        onCheckedChange = viewModel::onSaveProfileChange,
                    )
                    Text(
                        text = stringResource(R.string.connect_save_profile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                AnimatedVisibility(visible = state.saveProfile) {
                    OutlinedTextField(
                        value = state.profileName,
                        onValueChange = viewModel::onProfileNameChange,
                        label = { Text(stringResource(R.string.connect_profile_name)) },
                        placeholder = { Text(stringResource(R.string.connect_placeholder_profile_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = viewModel::connect,
                    enabled = !state.isConnecting,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.labelLarge)
                    }
                }

                TextButton(
                    onClick = { showManualFields = !showManualFields },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            if (showManualFields) {
                                stringResource(R.string.connect_action_hide_manual)
                            } else {
                                stringResource(R.string.connect_action_show_manual)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                AnimatedVisibility(visible = showManualFields) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = viewModel::onBaseUrlChange,
                            label = { Text(stringResource(R.string.connect_server_url_label)) },
                            placeholder = { Text(stringResource(R.string.connect_placeholder_server_url)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Cleartext transport warning
                        AnimatedVisibility(
                            visible = state.transportWarning != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            state.transportWarning?.let { warning ->
                                Text(
                                    text = warning,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.token,
                            onValueChange = viewModel::onTokenChange,
                            label = { Text(stringResource(R.string.settings_field_token)) },
                            placeholder = { Text(stringResource(R.string.connect_placeholder_token)) },
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
                                                stringResource(R.string.content_desc_hide_token)
                                            } else {
                                                stringResource(R.string.content_desc_show_token)
                                            },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
