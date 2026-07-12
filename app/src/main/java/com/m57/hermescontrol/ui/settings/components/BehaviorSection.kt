package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun BehaviorSection(
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
    screenCaptureProtectionEnabled: Boolean,
    onScreenCaptureProtectionChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_sec_behavior)) {
        BehaviorToggleRow(
            title = stringResource(R.string.settings_item_auto_reconnect),
            description = stringResource(R.string.settings_desc_auto_reconnect),
            checked = autoReconnect,
            onCheckedChange = onAutoReconnectChange,
        )
        BehaviorToggleRow(
            title = stringResource(R.string.settings_item_screen_capture_protection),
            description = stringResource(R.string.settings_desc_screen_capture_protection),
            checked = screenCaptureProtectionEnabled,
            onCheckedChange = onScreenCaptureProtectionChange,
        )
    }
}

@Composable
private fun BehaviorToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
    }
}
