package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.BiometricCredentialVault
import kotlinx.coroutines.launch

@Composable
fun AuthLoginScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthLoginViewModel =
        viewModel(
            factory =
                run {
                    val app = LocalContext.current.applicationContext as Application
                    AuthLoginViewModelFactory(app)
                },
        ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val biometricAvailable =
        remember(context) {
            BiometricCredentialVault.availability(context) is BiometricCredentialVault.Availability.Available
        }
    val hasSavedCreds =
        remember {
            runCatching { AuthManager.hasBiometricSavedCredentials() }.getOrDefault(false)
        }
    val savedUsername =
        remember {
            runCatching { AuthManager.biometricSavedUsername() }.getOrNull()
        }
    var biometricBusy by remember { mutableStateOf(false) }
    // Skip the post-connect "protect with biometrics" prompt when the
    // session was restored via biometric unlock — the vault already holds
    // these credentials and we must not re-prompt immediately.
    var offerBiometricSave by remember { mutableStateOf(true) }

    LaunchedEffect(state.connectionSuccess) {
        if (!state.connectionSuccess) return@LaunchedEffect
        val mode = state.authMode
        val canSave =
            offerBiometricSave &&
                activity != null &&
                (
                    mode == DashboardAuthMode.BASIC_AUTH ||
                        mode == DashboardAuthMode.ALL
                ) &&
                state.username.isNotBlank() &&
                state.password.isNotBlank() &&
                biometricAvailable
        if (canSave) {
            runCatching {
                val cipher = AuthManager.createBiometricEncryptCipher()
                val authenticated =
                    BiometricCredentialVault.authenticateSuspend(
                        activity = activity!!,
                        title = context.getString(R.string.auth_login_save_biometric_prompt_title),
                        subtitle = context.getString(R.string.auth_login_save_biometric_prompt_subtitle),
                        negativeButton = context.getString(R.string.auth_login_save_biometric_negative),
                        cipher = cipher,
                    )
                if (authenticated != null) {
                    AuthManager.saveBiometricCredentialsAfterAuth(
                        authenticated,
                        state.username,
                        state.password,
                    )
                }
            }
        }
        onConnected()
    }

    // Clear ephemeral connection state when screen leaves composition
    // (ViewModel is Activity-scoped so it survives navigation — without
    // this, stale connectionSuccess would auto-bounce the user on re-entry)
    DisposableEffect(Unit) {
        onDispose { viewModel.clearConnectionState() }
    }

    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
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
            // Hero icon
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }

            Text(
                text = stringResource(R.string.auth_login_title),
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
            )

            Text(
                text = stringResource(R.string.auth_login_desc),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (hasSavedCreds && biometricAvailable && activity != null) {
                Button(
                    onClick = {
                        if (biometricBusy) return@Button
                        biometricBusy = true
                        scope.launch {
                            runCatching {
                                val cipher = AuthManager.createBiometricDecryptCipher()
                                val authenticated =
                                    BiometricCredentialVault.authenticateSuspend(
                                        activity = activity,
                                        title = context.getString(R.string.chat_relogin_biometric_title),
                                        subtitle = context.getString(R.string.chat_relogin_biometric_subtitle),
                                        negativeButton = context.getString(R.string.chat_relogin_biometric_negative),
                                        cipher = cipher,
                                    ) ?: return@launch
                                val creds = AuthManager.unlockBiometricCredentialsAfterAuth(authenticated)
                                offerBiometricSave = false
                                viewModel.connectWithSavedBasicAuth(creds.username, creds.password)
                            }
                            biometricBusy = false
                        }
                    },
                    enabled = !state.isLoading && !state.probing && !biometricBusy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        if (savedUsername != null) {
                            stringResource(R.string.chat_relogin_biometric_unlock_as, savedUsername)
                        } else {
                            stringResource(R.string.chat_relogin_biometric_unlock)
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.chat_relogin_manual_divider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (state.loggedInProfiles.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.auth_login_existing_profiles_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.loggedInProfiles.forEach { profile ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.useExistingProfile(profile.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.auth_login_use_profile, profile.name))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Server URL field
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text(stringResource(R.string.auth_login_base_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = state.authMode == null,
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
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.error,
                            ),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Probe / probing indicator
            if (state.probing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.auth_login_probing_dashboard),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }

            // ── Dynamic fields based on auth mode ──

            // Token field — shown for TOKEN_ONLY and ALL
            AnimatedVisibility(
                visible =
                    state.authMode == DashboardAuthMode.TOKEN_ONLY ||
                        state.authMode == DashboardAuthMode.ALL,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text(stringResource(R.string.auth_login_token_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                                contentDescription = null,
                            )
                        }
                    },
                )
            }

            // Username field — shown for BASIC_AUTH and ALL
            AnimatedVisibility(
                visible =
                    state.authMode == DashboardAuthMode.BASIC_AUTH ||
                        state.authMode == DashboardAuthMode.ALL,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text(stringResource(R.string.auth_login_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Password field — shown for BASIC_AUTH and ALL
            AnimatedVisibility(
                visible =
                    state.authMode == DashboardAuthMode.BASIC_AUTH ||
                        state.authMode == DashboardAuthMode.ALL,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text(stringResource(R.string.auth_login_password_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }

            // OAuth "coming soon" notice — shown for OAUTH mode (issue #639)
            AnimatedVisibility(
                visible = state.authMode == DashboardAuthMode.OAUTH,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.auth_login_oauth_coming_soon),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        textAlign = TextAlign.Start,
                    )
                }
            }

            // Error message
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                state.errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.error,
                            ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connect / Probe button
            Button(
                onClick = {
                    if (state.authMode == null) {
                        viewModel.probe()
                    } else {
                        viewModel.connect()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.isLoading && !state.probing && state.authMode != DashboardAuthMode.OAUTH,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text =
                            if (state.authMode == null) {
                                stringResource(R.string.auth_login_action_probing)
                            } else {
                                stringResource(R.string.auth_login_action_connect)
                            },
                    )
                }
            }
        }
    }
}

private fun android.content.Context.findActivity(): FragmentActivity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
