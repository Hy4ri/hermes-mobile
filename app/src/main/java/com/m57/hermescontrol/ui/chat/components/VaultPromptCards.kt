package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.VaultCodePromptUi
import com.m57.hermescontrol.ui.chat.VaultSaveLoginPromptUi
import com.m57.hermescontrol.ui.chat.VaultUnlockPromptUi

/**
 * Interactive prompt cards for mid-turn Credential Vault operations (issue #1090).
 * Rendered inline in chat (matching ClarifyBubble pattern) to avoid blocking dialog scrims.
 */

@Composable
fun VaultUnlockCard(
    prompt: VaultUnlockPromptUi,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember(prompt.requestId) { mutableStateOf("") }
    var passwordVisible by remember(prompt.requestId) { mutableStateOf(false) }

    val displayName = prompt.displayName ?: prompt.backend ?: "Password Manager"

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("vault_unlock_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vault_unlock_title, displayName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = stringResource(R.string.vault_unlock_desc, displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.vault_unlock_password_label)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("vault_unlock_password_input"),
                singleLine = true,
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector =
                                if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                            contentDescription = null,
                        )
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("vault_unlock_dismiss"),
                ) {
                    Text(stringResource(R.string.vault_unlock_dismiss))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (password.isNotBlank()) {
                            onConfirm(password)
                        }
                    },
                    enabled = password.isNotBlank(),
                    modifier = Modifier.testTag("vault_unlock_confirm"),
                ) {
                    Text(stringResource(R.string.vault_unlock_action))
                }
            }
        }
    }
}

@Composable
fun VaultSaveLoginCard(
    prompt: VaultSaveLoginPromptUi,
    onConfirm: (identifier: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var identifier by remember(prompt.requestId) { mutableStateOf("") }
    var password by remember(prompt.requestId) { mutableStateOf("") }
    var passwordVisible by remember(prompt.requestId) { mutableStateOf(false) }

    val siteLabel = prompt.site ?: prompt.origin ?: "this site"

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("vault_save_login_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vault_save_login_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = stringResource(R.string.vault_save_login_desc, siteLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text(stringResource(R.string.vault_save_login_identifier)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("vault_save_login_identifier_input"),
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                    ),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.vault_save_login_password)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("vault_save_login_password_input"),
                singleLine = true,
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector =
                                if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                            contentDescription = null,
                        )
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("vault_save_login_dismiss"),
                ) {
                    Text(stringResource(R.string.vault_save_login_dismiss))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (identifier.isNotBlank() && password.isNotBlank()) {
                            onConfirm(identifier.trim(), password)
                        }
                    },
                    enabled = identifier.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.testTag("vault_save_login_confirm"),
                ) {
                    Text(stringResource(R.string.vault_save_login_action))
                }
            }
        }
    }
}

@Composable
fun VaultCodeCard(
    prompt: VaultCodePromptUi,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember(prompt.requestId) { mutableStateOf("") }

    val siteLabel = prompt.site ?: "site"
    val desc = prompt.hint?.takeIf { it.isNotBlank() } ?: stringResource(R.string.vault_code_desc, siteLabel)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("vault_code_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Pin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vault_code_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.vault_code_label)) },
                placeholder = { Text("123456") },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("vault_code_input"),
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Number,
                    ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("vault_code_dismiss"),
                ) {
                    Text(stringResource(R.string.vault_code_dismiss))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (code.isNotBlank()) {
                            onConfirm(code.trim())
                        }
                    },
                    enabled = code.isNotBlank(),
                    modifier = Modifier.testTag("vault_code_confirm"),
                ) {
                    Text(stringResource(R.string.vault_code_action))
                }
            }
        }
    }
}
