package com.m57.hermescontrol.ui.channels.components

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.channels.ConfigureForm
import com.m57.hermescontrol.ui.common.ConfirmDialog
import com.m57.hermescontrol.ui.common.ManageableItemCard
import com.m57.hermescontrol.ui.common.ManageableToggle
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import kotlinx.coroutines.launch

private data class PlatformStateInfo(
    val label: String,
    val statusBadgeType: StatusBadgeType,
    val icon: ImageVector,
)

private fun platformStateInfo(state: String): PlatformStateInfo =
    when (state) {
        "connected" -> {
            PlatformStateInfo("Connected", StatusBadgeType.SUCCESS, Icons.Filled.CheckCircle)
        }

        "pending_restart" -> {
            PlatformStateInfo("Restart to apply", StatusBadgeType.WARNING, Icons.Filled.Refresh)
        }

        "gateway_stopped" -> {
            PlatformStateInfo("Gateway stopped", StatusBadgeType.WARNING, Icons.Filled.RadioButtonChecked)
        }

        "startup_failed" -> {
            PlatformStateInfo("Start failed", StatusBadgeType.ERROR, Icons.Filled.Warning)
        }

        "disconnected" -> {
            PlatformStateInfo("Disconnected", StatusBadgeType.WARNING, Icons.Filled.RadioButtonChecked)
        }

        "not_configured" -> {
            PlatformStateInfo("Not configured", StatusBadgeType.NEUTRAL, Icons.Filled.RadioButtonChecked)
        }

        "disabled" -> {
            PlatformStateInfo("Disabled", StatusBadgeType.NEUTRAL, Icons.Filled.RadioButtonChecked)
        }

        "fatal" -> {
            PlatformStateInfo("Error", StatusBadgeType.ERROR, Icons.Filled.Warning)
        }

        else -> {
            PlatformStateInfo(state, StatusBadgeType.NEUTRAL, Icons.Filled.RadioButtonChecked)
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val stateInfo = platformStateInfo(platform.state)
    val statusColors = LocalHermesStatusColors.current
    val iconTint =
        when (stateInfo.statusBadgeType) {
            StatusBadgeType.SUCCESS -> statusColors.success
            StatusBadgeType.WARNING -> statusColors.warning
            StatusBadgeType.ERROR -> statusColors.error
            StatusBadgeType.INFO -> statusColors.info
            StatusBadgeType.NEUTRAL -> statusColors.neutral
        }

    ManageableItemCard(
        title = platform.name,
        subtitle = platform.description?.takeIf { it.isNotBlank() },
        badges = {
            Icon(
                imageVector = stateInfo.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
            StatusBadge(
                text = stateInfo.label,
                status = stateInfo.statusBadgeType,
            )
        },
        toggle =
            ManageableToggle(
                checked = platform.enabled,
                onCheckedChange = onToggle,
                busy = isToggling,
            ),
        body = {
            platform.ingressUrl?.takeIf { it.isNotBlank() }?.let { ingressUrl ->
                Text(
                    text = stringResource(R.string.channels_ingress_url),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ingressUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, ingressUrl)))
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.channels_copy_ingress_url),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (!platform.errorMessage.isNullOrBlank()) {
                Text(
                    text = platform.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (platform.id == "telegram" && !platform.configured) {
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
                        text = stringResource(R.string.platform_setup_qr),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (showConfigureForm) {
                ConfigureForm(
                    platform = platform,
                    onSave = onSave,
                    onClose = { showConfigureForm = false },
                )
            }
        },
        actions = {
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
                        contentDescription = stringResource(R.string.platform_disconnect_desc),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )

    if (showRemoveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.platform_disconnect_confirm_title, platform.name),
            message = stringResource(R.string.platform_disconnect_confirm_text),
            onConfirm = {
                showRemoveConfirm = false
                onRemove()
            },
            onDismiss = { showRemoveConfirm = false },
            confirmText = stringResource(R.string.providers_action_disconnect),
            dismissText = stringResource(R.string.action_cancel),
            isDestructive = true,
        )
    }
}
