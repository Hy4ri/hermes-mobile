package com.m57.hermescontrol.ui.chat.components

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ReloginUiState

/**
 * Secure password dialog for a pending `sudo.request` (issue #524).
 * The backend blocked the turn waiting for the sudo password — previously
 * mobile dropped the event and the agent hung forever.
 */
@Composable
fun SudoPromptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_sudo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.chat_sudo_body))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.chat_sudo_password)) },
                    modifier = Modifier.fillMaxWidth().testTag("sudo_password_input"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.isNotBlank()) {
                        onConfirm(password)
                    }
                },
                enabled = password.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        },
    )
}

/**
 * Secure value dialog for a pending `secret.request` (issue #524).
 * The backend blocked the turn waiting for a secret (token/password) —
 * previously mobile dropped the event and the agent hung forever.
 */
@Composable
fun SecretPromptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    envVar: String? = null,
    prompt: String? = null,
) {
    var secret by remember { mutableStateOf("") }
    val titleText = envVar?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_secret_title)
    val bodyText = prompt?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_secret_body)
    val labelText = envVar?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_secret_value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = bodyText)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(labelText) },
                    modifier = Modifier.fillMaxWidth().testTag("secret_value_input"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (secret.isNotBlank()) {
                        onConfirm(secret)
                    }
                },
                enabled = secret.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        },
    )
}

/**
 * Re-authentication dialog shown when the connection enters AUTH_EXPIRED. It offers the sign-in
 * method the gateway advertises (issue #1641): the password form for password providers, the
 * system browser (OIDC, Nous Portal, ...) for the others, with a chooser when there are several.
 */
@Composable
fun ReloginDialog(
    state: ReloginUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSubmitPassword: (String, String) -> Unit,
    onStartBrowser: ((String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val emptyCredentialsError = stringResource(R.string.chat_relogin_error_empty)
    val statusColors = LocalHermesStatusColors.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val errorMessage = localError ?: state.error
    val providerLabel = state.selected?.displayName ?: state.selectedProvider.orEmpty()

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.chat_relogin_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    state.probing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = statusColors.info,
                        )
                    }

                    state.unreachable -> {
                        Text(
                            text = stringResource(R.string.auth_login_error_unreachable),
                            color = statusColors.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        if (state.providers.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.providers.forEach { option ->
                                    FilterChip(
                                        selected = option.name == state.selectedProvider,
                                        onClick = {
                                            localError = null
                                            onSelectProvider(option.name)
                                        },
                                        enabled = !state.busy,
                                        label = { Text(option.displayName) },
                                    )
                                }
                            }
                        }
                        if (state.usesPassword) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = {
                                    username = it
                                    localError = null
                                },
                                label = { Text(stringResource(R.string.chat_relogin_username)) },
                                singleLine = true,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    localError = null
                                },
                                label = { Text(stringResource(R.string.chat_relogin_password)) },
                                singleLine = true,
                                enabled = !state.busy,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text =
                                    if (state.browserWaiting) {
                                        stringResource(R.string.auth_login_browser_waiting)
                                    } else {
                                        stringResource(R.string.auth_login_browser_desc, providerLabel)
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!state.browserSupported) {
                                Text(
                                    text = stringResource(R.string.auth_login_error_browser_unsupported),
                                    color = statusColors.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = statusColors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            when {
                state.probing -> {}

                state.unreachable -> {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                state.usesPassword -> {
                    TextButton(
                        enabled = !state.busy,
                        onClick = {
                            if (username.isBlank() || password.isBlank()) {
                                localError = emptyCredentialsError
                                return@TextButton
                            }
                            localError = null
                            onSubmitPassword(username, password)
                        },
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = statusColors.info,
                            )
                        } else {
                            Text(stringResource(R.string.chat_relogin_submit))
                        }
                    }
                }

                else -> {
                    TextButton(
                        enabled = !state.busy && state.browserSupported,
                        onClick = {
                            onStartBrowser { url ->
                                CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                            }
                        },
                    ) {
                        if (state.browserWaiting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = statusColors.info,
                            )
                        } else {
                            Text(stringResource(R.string.auth_login_action_browser, providerLabel))
                        }
                    }
                }
            }
        },
        dismissButton = {
            // Cancel also aborts a browser sign-in that waits for the redirect.
            TextButton(
                enabled = !state.submitting,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.chat_relogin_cancel))
            }
        },
    )
}
