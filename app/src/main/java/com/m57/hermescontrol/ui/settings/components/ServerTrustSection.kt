package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.ServerTrust
import com.m57.hermescontrol.ui.settings.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.m57.hermescontrol.ui.common.ExpressiveSwitch as Switch

@Composable
internal fun ServerTrustSection() {
    val enabled by ServerTrust.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    ServerTrustContent(
        enabled = enabled,
        saving = saving,
        restartRequired = enabled != ServerTrust.active,
        saveFailed = failed,
        onToggle = { value ->
            saving = true
            failed = false
            scope.launch {
                // Finish saving the next-start preference even if this page leaves composition.
                failed =
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { ServerTrust.setEnabled(value) }.isFailure
                    }
                saving = false
            }
        },
    )
}

@Composable
internal fun ServerTrustContent(
    enabled: Boolean,
    saving: Boolean,
    restartRequired: Boolean,
    saveFailed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SectionCard {
        Column(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = enabled,
                    enabled = !saving,
                    role = Role.Switch,
                    onValueChange = onToggle,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The title shares the row with the switch; the long description gets the full card width.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.trust_user_cas_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = null, enabled = !saving)
            }
            Text(
                stringResource(R.string.trust_user_cas_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (restartRequired) {
            Text(
                stringResource(R.string.trust_user_cas_restart_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.trust_user_cas_save_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
