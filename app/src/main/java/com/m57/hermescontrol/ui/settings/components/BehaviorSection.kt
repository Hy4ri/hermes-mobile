package com.m57.hermescontrol.ui.settings.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun BehaviorSection(
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
    keepConnectedInBackground: Boolean,
    onKeepConnectedInBackgroundChange: (Boolean) -> Unit,
    notifySessionCompletions: Boolean,
    onNotifySessionCompletionsChange: (Boolean) -> Unit,
    restoreLastSession: Boolean,
    onRestoreLastSessionChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Turning session-complete alerts on needs POST_NOTIFICATIONS (Android
    // 13+). The chat screen requests it on open, but the toggle lives in
    // Settings — gate here so enabling never lands in a state where the OS
    // silently blocks what the user just asked for. Denial reverts the toggle.
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onNotifySessionCompletionsChange(true)
        }
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_keep_connected_in_background_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_keep_connected_in_background_desc),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = keepConnectedInBackground,
                onCheckedChange = onKeepConnectedInBackgroundChange,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notify_session_completions_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_notify_session_completions_desc),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = notifySessionCompletions,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        onNotifySessionCompletionsChange(false)
                    } else {
                        val permission = Manifest.permission.POST_NOTIFICATIONS
                        val granted =
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(context, permission) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            onNotifySessionCompletionsChange(true)
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    }
                },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_restore_session),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_restore_session),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = restoreLastSession,
                onCheckedChange = onRestoreLastSessionChange,
            )
        }
    }
}
