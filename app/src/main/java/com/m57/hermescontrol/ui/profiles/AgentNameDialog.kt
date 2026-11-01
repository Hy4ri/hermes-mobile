package com.m57.hermescontrol.ui.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.local.AgentDisplayName

/**
 * Edits the device-local agent name for one connection + server profile (#1747).
 * Saving an empty field removes the custom name, so the profile name shows again.
 */
@Composable
internal fun AgentNameDialog(
    connectionProfileId: String?,
    profileName: String,
    onDismiss: () -> Unit,
) {
    var text by remember(connectionProfileId, profileName) {
        mutableStateOf(AgentDisplayName.custom(connectionProfileId, profileName).orEmpty())
    }
    val fallback = AgentDisplayName.resolve(connectionProfileId, profileName, emptyMap())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(AgentDisplayName.MAX_LENGTH) },
                label = { Text(stringResource(R.string.agent_name_label, profileName)) },
                placeholder = { Text(fallback) },
                supportingText = { Text(stringResource(R.string.agent_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("agent_name_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    AgentDisplayName.set(connectionProfileId, profileName, text)
                    onDismiss()
                },
                modifier = Modifier.testTag("agent_name_save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
