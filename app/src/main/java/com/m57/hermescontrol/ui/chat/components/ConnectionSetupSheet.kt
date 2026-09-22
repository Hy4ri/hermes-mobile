package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSetupSheet(
    operation: ConnectionOperationSnapshot,
    onRespond: (String, Map<String, String>, Boolean) -> Unit,
    onContinue: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = operation.targets.firstOrNull { it.requiredEnv.isNotEmpty() }
        ?: operation.targets.firstOrNull() ?: return
    val values = remember(operation.opId, operation.seq) { mutableStateMapOf<String, String>() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("connection_setup_sheet")) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(target.name, modifier = Modifier.testTag("connection_setup_title"))
            target.instructions?.let { Text(it, Modifier.padding(top = 8.dp)) }
            target.requiredEnv.forEach { field ->
                OutlinedTextField(
                    value = values[field.name] ?: field.defaultValue.orEmpty(),
                    onValueChange = { values[field.name] = it },
                    label = { Text(field.prompt ?: field.name) },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("connection_env_${field.name}"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(onClick = { onRespond(target.name, values.toMap(), false) }) {
                    Text(stringResource(R.string.connection_setup_skip))
                }
                Button(onClick = { onRespond(target.name, values.toMap(), true) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.connection_setup_continue))
                }
            }
            if (target.connectUrl != null) {
                OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.connection_setup_open_browser))
                }
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.connection_setup_done))
            }
        }
    }
}
