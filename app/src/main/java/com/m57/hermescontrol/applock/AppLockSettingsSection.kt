package com.m57.hermescontrol.applock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.DropdownMenu
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun AppLockSettingsSection() {
    val controller = LocalAppLockController.current ?: return
    val session = controller.session
    val enabled = session.policy.enabled
    val busy = session.pendingAuth != null || session.credentialRequest != null
    var showTimeouts by remember { mutableStateOf(false) }
    val timeoutLabels =
        listOf(
            R.string.app_lock_immediately,
            R.string.app_lock_30_seconds,
            R.string.app_lock_1_minute,
            R.string.app_lock_5_minutes,
        )
    SectionCard {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(enabled, enabled = !busy, role = Role.Switch) { checked ->
                        controller.authenticate(if (checked) AppLockAuthAction.ENABLE else AppLockAuthAction.DISABLE)
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_lock_title), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null, enabled = !busy)
        }
        Text(stringResource(R.string.app_lock_description), style = MaterialTheme.typography.bodySmall)
        Column {
            OutlinedButton(onClick = { showTimeouts = true }, enabled = enabled && !busy) {
                val index = AppLockPolicy.TIMEOUTS.indexOf(session.policy.timeoutMs).coerceAtLeast(0)
                Text(stringResource(R.string.app_lock_timeout, stringResource(timeoutLabels[index])))
            }
            DropdownMenu(expanded = showTimeouts, onDismissRequest = { showTimeouts = false }) {
                AppLockPolicy.TIMEOUTS.forEachIndexed { index, timeout ->
                    DropdownMenuItem(
                        text = { Text(stringResource(timeoutLabels[index])) },
                        onClick = {
                            showTimeouts = false
                            session.changeTimeout(timeout)
                        },
                    )
                }
            }
        }
        Text(stringResource(R.string.app_lock_notifications), style = MaterialTheme.typography.bodySmall)
        session.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}
