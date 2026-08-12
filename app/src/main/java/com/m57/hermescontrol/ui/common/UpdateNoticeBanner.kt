package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Chat-screen banner for the launch update check (issue #890): a newer
 * Hermes Mobile release exists. "Update" jumps into the About-tab install
 * flow; "Later" dismisses for the session (the banner returns next launch).
 */
@Composable
fun UpdateNoticeBanner(
    latestTag: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Update,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(R.string.update_notice_banner_text, latestTag),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpdate) {
            Text(
                text = stringResource(R.string.update_notice_banner_action),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.action_dismiss),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
