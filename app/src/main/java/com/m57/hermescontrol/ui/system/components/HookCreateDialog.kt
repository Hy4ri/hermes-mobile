package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing

@Composable
fun HookCreateDialog(
    isOpen: Boolean,
    event: String,
    command: String,
    matcher: String,
    timeout: String,
    approve: Boolean,
    isCreating: Boolean,
    onEventChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onMatcherChange: (String) -> Unit,
    onTimeoutChange: (String) -> Unit,
    onApproveChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return
    val spacing = LocalSpacing.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.system_hooks_modal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedTextField(
                    value = event,
                    onValueChange = onEventChange,
                    label = { Text(stringResource(R.string.system_hooks_field_event)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    label = { Text(stringResource(R.string.system_hooks_field_command)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = matcher,
                    onValueChange = onMatcherChange,
                    label = { Text(stringResource(R.string.system_hooks_field_matcher)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = onTimeoutChange,
                    label = { Text(stringResource(R.string.system_hooks_field_timeout)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = approve,
                        onCheckedChange = onApproveChange,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = stringResource(R.string.system_hooks_field_approve),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.system_hooks_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCreate,
                enabled = !isCreating && command.isNotBlank(),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(spacing.sm))
                }
                Text(stringResource(R.string.system_hooks_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.system_confirm_cancel))
            }
        },
    )
}
