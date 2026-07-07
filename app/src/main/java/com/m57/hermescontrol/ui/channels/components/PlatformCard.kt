package com.m57.hermescontrol.ui.channels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.ui.channels.ConfigureForm

private data class StateStyle(
    val label: String,
    val color: Color,
    val icon: ImageVector,
)

@Composable
private fun platformStateStyle(state: String): StateStyle =
    when (state) {
        "connected" -> StateStyle("Connected", Color(0xFF4CAF50), Icons.Filled.CheckCircle)
        "pending_restart" -> StateStyle("Restart to apply", Color(0xFFFFC107), Icons.Filled.Refresh)
        "gateway_stopped" -> StateStyle("Gateway stopped", Color(0xFFFF9800), Icons.Filled.RadioButtonChecked)
        "startup_failed" -> StateStyle("Start failed", Color(0xFFF44336), Icons.Filled.Warning)
        "disconnected" -> StateStyle("Disconnected", Color(0xFFFF9800), Icons.Filled.RadioButtonChecked)
        "not_configured" -> StateStyle("Not configured", Color(0xFF9E9E9E), Icons.Filled.RadioButtonChecked)
        "disabled" -> StateStyle("Disabled", Color(0xFF9E9E9E), Icons.Filled.RadioButtonChecked)
        "fatal" -> StateStyle("Error", Color(0xFFF44336), Icons.Filled.Warning)
        else -> StateStyle(state, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.RadioButtonChecked)
    }

@Composable
private fun StateBadge(state: String) {
    val style = platformStateStyle(state)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = style.color.copy(alpha = 0.15f),
    ) {
        Text(
            text = style.label,
            color = style.color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun PlatformCard(
    platform: MessagingPlatform,
    isToggling: Boolean,
    isTesting: Boolean,
    isRemoving: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: (MessagingPlatformUpdate) -> Unit,
    onRemove: () -> Unit,
    onStartOnboarding: () -> Unit,
) {
    var showConfigureForm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val style = platformStateStyle(platform.state)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Top row: icon + name + badge + switch ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.color,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = platform.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateBadge(state = platform.state)
                        }
                    }
                }

                if (isToggling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(
                        checked = platform.enabled,
                        onCheckedChange = onToggle,
                    )
                }
            }

            // ── Description ──
            if (!platform.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = platform.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Error message ──
            if (!platform.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = platform.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ── Action buttons ──
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.channels_action_test),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Button(
                    onClick = { showConfigureForm = !showConfigureForm },
                ) {
                    Text(
                        text =
                            if (showConfigureForm) {
                                stringResource(R.string.action_cancel)
                            } else {
                                stringResource(R.string.channels_action_configure)
                            },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // Remove button
                IconButton(
                    onClick = { showRemoveConfirm = true },
                    enabled = !isRemoving,
                ) {
                    if (isRemoving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Remove platform",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Telegram onboarding (only for unconfigured telegram) ──
            if (platform.id == "telegram" && !platform.configured) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onStartOnboarding,
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Set up with QR",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // ── Configure form ──
            if (showConfigureForm) {
                ConfigureForm(
                    platform = platform,
                    onSave = onSave,
                    onClose = { showConfigureForm = false },
                )
            }
        }
    }

    // Remove confirmation
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove ${platform.name}?") },
            text = { Text("This will permanently remove this platform's configuration.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
