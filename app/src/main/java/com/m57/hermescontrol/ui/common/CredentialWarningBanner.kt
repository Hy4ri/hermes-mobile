package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import com.m57.hermescontrol.theme.LocalHermesStatusColors

/**
 * Banner shown when the backend reports a `credential_warning` (the mobile
 * equivalent of the desktop `requestDesktopOnboarding` re-trigger, issue #534).
 * Tapping "Fix" deep-links to ProvidersScreen; dismissing clears it globally.
 */
@Composable
fun CredentialWarningBanner(
    warning: String,
    onFix: () -> Unit,
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
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = LocalHermesStatusColors.current.warning,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) {
            Text(
                text = stringResource(R.string.providers_action_fix),
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
