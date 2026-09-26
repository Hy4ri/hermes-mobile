package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.CustomHeaders
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.ServerHeaders
import com.m57.hermescontrol.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException

@Composable
fun CustomHeadersButton(
    baseUrl: String,
    enabled: Boolean = true,
    onSaved: () -> Unit,
) {
    val endpoint = remember(baseUrl) { runCatching { ServerEndpoint.parseForBuild(baseUrl) }.getOrNull() }
    var editing by remember(baseUrl) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { editing = true },
        enabled = enabled && endpoint != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.custom_headers_title))
    }
    if (editing && endpoint != null) {
        CustomHeadersDialog(
            baseUrl = endpoint.baseUrl,
            onDismiss = { editing = false },
            onSaved = {
                editing = false
                onSaved()
            },
        )
    }
}

@Composable
private fun CustomHeadersDialog(
    baseUrl: HttpUrl,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var rows by remember { mutableStateOf(ServerHeaders.get(baseUrl).entries.toList()) }
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.custom_headers_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(baseUrl.toString(), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.custom_headers_description))
                rows.forEachIndexed { index, (name, value) ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = { new ->
                            rows = rows.mapIndexed { i, row -> if (i == index) new to row.second else row }
                            error = null
                        },
                        label = { Text(stringResource(R.string.custom_headers_name)) },
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { new ->
                            rows = rows.mapIndexed { i, row -> if (i == index) row.first to new else row }
                            error = null
                        },
                        label = { Text(stringResource(R.string.custom_headers_value)) },
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation =
                            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    imageVector =
                                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription =
                                        stringResource(
                                            if (visible) R.string.custom_headers_hide else R.string.custom_headers_show,
                                        ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { deleting = index }, enabled = !saving) {
                        Text(stringResource(R.string.custom_headers_remove))
                    }
                }
                OutlinedButton(onClick = { rows = rows + ("" to "") }, enabled = !saving) {
                    Text(stringResource(R.string.custom_headers_add))
                }
                if (rows.isEmpty()) {
                    TextButton(
                        onClick = {
                            rows = listOf("CF-Access-Client-Id" to "", "CF-Access-Client-Secret" to "")
                        },
                        enabled = !saving,
                    ) {
                        Text(stringResource(R.string.custom_headers_cloudflare))
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    val headers = runCatching { CustomHeaders.parse(rows) }.getOrNull()
                    if (headers == null) {
                        error = R.string.custom_headers_invalid
                    } else {
                        saving = true
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) { ServerHeaders.save(baseUrl, headers) }
                                onSaved()
                            } catch (_: IOException) {
                                error = R.string.custom_headers_save_failed
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    deleting?.let { index ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.custom_headers_remove)) },
            text = { Text(stringResource(R.string.custom_headers_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    rows = rows.filterIndexed { i, _ -> i != index }
                    deleting = null
                    error = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
