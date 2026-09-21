package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.fragment.app.FragmentActivity
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.BiometricCredentialVault
import com.m57.hermescontrol.theme.LocalHermesStatusColors

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

/** Re-authentication dialog shown when the connection enters AUTH_EXPIRED. */
@Composable
fun ReloginDialog(
    onDismiss: () -> Unit,
    onRelogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
) {
    val emptyCredentialsError = stringResource(R.string.chat_relogin_error_empty)
    val biometricErrorTemplate = stringResource(R.string.chat_relogin_biometric_error)
    val statusColors = LocalHermesStatusColors.current
    val context = LocalContext.current
    val activity = context.findFragmentActivity()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val biometricAvailable =
        remember(context) {
            BiometricCredentialVault.availability(context) is BiometricCredentialVault.Availability.Available
        }
    val hasSaved =
        remember {
            runCatching { AuthManager.hasBiometricSavedCredentials() }.getOrDefault(false)
        }
    val savedUsername =
        remember {
            runCatching { AuthManager.biometricSavedUsername() }.getOrNull()
        }

    fun submit(
        user: String,
        pass: String,
        offerBiometricSave: Boolean,
    ) {
        isLoading = true
        errorMessage = null
        onRelogin(user, pass) { success, error ->
            if (!success) {
                isLoading = false
                errorMessage = error ?: "Unknown error"
                return@onRelogin
            }
            if (
                offerBiometricSave &&
                biometricAvailable &&
                activity != null
            ) {
                runCatching {
                    val cipher = AuthManager.createBiometricEncryptCipher()
                    BiometricCredentialVault.authenticate(
                        activity = activity,
                        title = context.getString(R.string.auth_login_save_biometric_prompt_title),
                        subtitle = context.getString(R.string.auth_login_save_biometric_prompt_subtitle),
                        negativeButton = context.getString(R.string.auth_login_save_biometric_negative),
                        cipher = cipher,
                        onSuccess = { authenticatedCipher ->
                            runCatching {
                                AuthManager.saveBiometricCredentialsAfterAuth(
                                    authenticatedCipher,
                                    user,
                                    pass,
                                )
                            }
                            isLoading = false
                            onDismiss()
                        },
                        onError = {
                            isLoading = false
                            onDismiss()
                        },
                        onCancel = {
                            isLoading = false
                            onDismiss()
                        },
                    )
                }.onFailure {
                    isLoading = false
                    onDismiss()
                }
            } else {
                isLoading = false
                onDismiss()
            }
        }
    }

    fun unlockWithBiometrics() {
        val host = activity ?: return
        isLoading = true
        errorMessage = null
        runCatching {
            val cipher = AuthManager.createBiometricDecryptCipher()
            BiometricCredentialVault.authenticate(
                activity = host,
                title = context.getString(R.string.chat_relogin_biometric_title),
                subtitle = context.getString(R.string.chat_relogin_biometric_subtitle),
                negativeButton = context.getString(R.string.chat_relogin_biometric_negative),
                cipher = cipher,
                onSuccess = { authenticatedCipher ->
                    runCatching {
                        AuthManager.unlockBiometricCredentialsAfterAuth(authenticatedCipher)
                    }.onSuccess { creds ->
                        submit(creds.username, creds.password, offerBiometricSave = false)
                    }.onFailure { e ->
                        isLoading = false
                        errorMessage = biometricErrorTemplate.format(e.message ?: "decrypt failed")
                    }
                },
                onError = { msg ->
                    isLoading = false
                    errorMessage = biometricErrorTemplate.format(msg)
                },
                onCancel = {
                    isLoading = false
                },
            )
        }.onFailure { e ->
            isLoading = false
            errorMessage = biometricErrorTemplate.format(e.message ?: "unavailable")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_relogin_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (hasSaved && biometricAvailable) {
                    Button(
                        onClick = { unlockWithBiometrics() },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
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
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.chat_relogin_username)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.chat_relogin_password)) },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = statusColors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = emptyCredentialsError
                        return@TextButton
                    }
                    submit(username.trim(), password, offerBiometricSave = true)
                },
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = statusColors.info,
                    )
                } else {
                    Text(stringResource(R.string.chat_relogin_submit))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isLoading,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.chat_relogin_cancel))
            }
        },
    )
}

private fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
