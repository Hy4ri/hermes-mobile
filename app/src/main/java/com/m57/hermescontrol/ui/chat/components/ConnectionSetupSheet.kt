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
import androidx.compose.ui.text.input.VisualTransformation
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
    val target = operation.targets.firstOrNull { it.state == com.m57.hermescontrol.data.model.ConnectionTargetState.PENDING }
        ?: operation.targets.firstOrNull { it.state == com.m57.hermescontrol.data.model.ConnectionTargetState.INITIATED }
        ?: operation.targets.firstOrNull() ?: return
    val values = remember(operation.opId, target.name) { mutableStateMapOf<String, String>() }
    val missingRequired = target.requiredEnv.firstOrNull {
        it.required && (it.secret || it.defaultValue.isNullOrBlank()) && values[it.name].orEmpty().isBlank()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("connection_setup_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(target.name, modifier = Modifier.testTag("connection_setup_title"))
            target.instructions?.let { Text(it, Modifier.padding(top = 8.dp)) }
            target.requiredEnv.forEach { field ->
                OutlinedTextField(
                    value = values[field.name] ?: if (field.secret) "" else field.defaultValue.orEmpty(),
                    onValueChange = { values[field.name] = it },
                    label = { Text(field.prompt ?: field.name) },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("connection_env_${field.name}"),
                )
            }
            missingRequired?.let { field ->
                Text(
                    text = stringResource(R.string.connection_setup_required, field.prompt ?: field.name),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(onClick = { onRespond(target.name, emptyMap(), false) }) {
                    Text(stringResource(R.string.connection_setup_skip))
                }
                Button(
                    onClick = { onRespond(target.name, values.toMap(), true) },
                    enabled = missingRequired == null,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.connection_setup_continue))
                }
            }
            if (target.connectUrl != null) {
                OutlinedButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.connection_setup_open_browser))
                }
            }
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.connection_setup_done))
            }
        }
    }
}
