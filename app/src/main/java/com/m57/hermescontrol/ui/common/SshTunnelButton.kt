package com.m57.hermescontrol.ui.common

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.remote.requiresLocalNetworkPermission
import com.m57.hermescontrol.data.tunnel.SshAuthMode
import com.m57.hermescontrol.data.tunnel.SshTunnelConfig
import com.m57.hermescontrol.data.tunnel.SshTunnelController
import com.m57.hermescontrol.data.tunnel.SshTunnelFailure
import com.m57.hermescontrol.data.tunnel.SshTunnelProblem
import com.m57.hermescontrol.data.tunnel.SshTunnelState
import com.m57.hermescontrol.data.tunnel.SshTunnelStore
import com.m57.hermescontrol.data.tunnel.tunnelOriginOf
import com.m57.hermescontrol.theme.HermesControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** String resource for a tunnel failure, shared with the sign-in flow. */
fun sshTunnelFailureMessage(reason: SshTunnelFailure): Int =
    when (reason) {
        SshTunnelFailure.UNREACHABLE -> R.string.ssh_tunnel_error_unreachable
        SshTunnelFailure.AUTHENTICATION -> R.string.ssh_tunnel_error_authentication
        SshTunnelFailure.HOST_KEY_CHANGED -> R.string.ssh_tunnel_error_host_key_changed
        SshTunnelFailure.FORWARDING -> R.string.ssh_tunnel_error_forwarding
        SshTunnelFailure.UNKNOWN -> R.string.ssh_tunnel_error_unknown
    }

private fun sshTunnelProblemMessage(problem: SshTunnelProblem): Int =
    when (problem) {
        SshTunnelProblem.SSH_HOST -> R.string.ssh_tunnel_error_host
        SshTunnelProblem.SSH_PORT -> R.string.ssh_tunnel_error_port
        SshTunnelProblem.USERNAME -> R.string.ssh_tunnel_error_username
        SshTunnelProblem.PASSWORD -> R.string.ssh_tunnel_error_password
        SshTunnelProblem.PRIVATE_KEY -> R.string.ssh_tunnel_error_private_key
        SshTunnelProblem.REMOTE_HOST -> R.string.ssh_tunnel_error_remote_host
        SshTunnelProblem.REMOTE_PORT -> R.string.ssh_tunnel_error_remote_port
    }

/**
 * Opens the SSH tunnel editor for the dashboard URL currently being configured.
 *
 * The settings belong to that URL's origin and are stored encrypted; enabling
 * them never changes the URL itself — only the socket that carries it.
 */
@Composable
fun SshTunnelButton(
    baseUrl: String,
    enabled: Boolean = true,
    onSaved: () -> Unit,
) {
    val origin = remember(baseUrl) { tunnelOriginOf(baseUrl) }
    var editing by remember(baseUrl) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { editing = true },
        enabled = enabled && origin != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.ssh_tunnel_title))
    }
    if (editing && origin != null) {
        SshTunnelDialog(
            origin = origin,
            dashboardHost = remember(baseUrl) { runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty() },
            onClosed = { saved ->
                editing = false
                if (saved) onSaved()
            },
        )
    }
}

@Composable
private fun SshTunnelDialog(
    origin: String,
    dashboardHost: String,
    onClosed: (saved: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var persistedConfig by remember(origin) { mutableStateOf(SshTunnelStore.get(origin)) }
    var draft by remember(origin) {
        mutableStateOf(
            persistedConfig ?: SshTunnelConfig(
                host = dashboardHost,
                username = "",
                remoteHost = SshTunnelConfig.DEFAULT_REMOTE_HOST,
                remotePort =
                    runCatching { java.net.URI(origin).port }
                        .getOrDefault(SshTunnelConfig.DEFAULT_REMOTE_PORT)
                        .takeIf { it > 0 } ?: SshTunnelConfig.DEFAULT_REMOTE_PORT,
            ),
        )
    }
    var error by remember(origin) {
        mutableStateOf<Int?>(if (SshTunnelStore.loadFailed) R.string.ssh_tunnel_error_load else null)
    }
    var saving by remember(origin) { mutableStateOf(false) }
    var saved by remember(origin) { mutableStateOf(false) }
    val tunnelState by SshTunnelController.state.collectAsStateWithLifecycle()
    val activeOrigin by SshTunnelController.activeOrigin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var pendingSaveConfig by remember(origin) { mutableStateOf<SshTunnelConfig?>(null) }

    suspend fun saveConfig(config: SshTunnelConfig) {
        saving = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                SshTunnelStore.save(origin, config)
                SshTunnelController.applyForBaseUrl(origin)
            }
            persistedConfig = config
            saved = true
        } catch (_: IOException) {
            error = R.string.ssh_tunnel_error_save
        } finally {
            saving = false
        }
    }

    val lanPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingSaveConfig
            pendingSaveConfig = null
            if (pending != null) {
                if (granted) {
                    scope.launch { saveConfig(pending) }
                } else {
                    error = R.string.connect_error_lan_permission_denied
                }
            }
        }

    fun persist() {
        if (SshTunnelStore.loadFailed) {
            error = R.string.ssh_tunnel_error_load
            return
        }
        val toSave = draft
        val problem = toSave.problem()
        if (toSave.enabled && problem != null) {
            error = sshTunnelProblemMessage(problem)
            return
        }
        error = null
        saving = true
        scope.launch {
            // Host classification can resolve DNS; never run it on the UI thread.
            val requiresLan =
                withContext(Dispatchers.IO) {
                    toSave.enabled && requiresLocalNetworkPermission(Build.VERSION.SDK_INT, origin, toSave.host)
                }
            if (requiresLan && ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingSaveConfig = toSave
                saving = false
                lanPermissionLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                return@launch
            }
            saveConfig(toSave)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClosed(saved) },
        title = { Text(stringResource(R.string.ssh_tunnel_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ssh_tunnel_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.ssh_tunnel_enable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = draft.enabled,
                        enabled = !saving,
                        onCheckedChange = {
                            draft =
                                draft.copy(
                                    enabled = it,
                                    // First enable seeds the SSH host from the dashboard host: the
                                    // common case is a gateway on the same box, bound to loopback.
                                    host = if (it && draft.host.isBlank()) dashboardHost else draft.host,
                                )
                            error = null
                        },
                    )
                }
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = {
                        draft = draft.copy(host = it.trim())
                        error = null
                    },
                    label = { Text(stringResource(R.string.ssh_tunnel_host)) },
                    singleLine = true,
                    enabled = draft.enabled && !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.port.toString(),
                        onValueChange = { value ->
                            draft = draft.copy(port = value.filter { it.isDigit() }.toIntOrNull() ?: 0)
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_port)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.username,
                        onValueChange = {
                            draft = draft.copy(username = it.trim())
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_username)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.authMode == SshAuthMode.PASSWORD,
                        onClick = {
                            draft = draft.copy(authMode = SshAuthMode.PASSWORD)
                            error = null
                        },
                        enabled = draft.enabled && !saving,
                        label = { Text(stringResource(R.string.ssh_tunnel_auth_password)) },
                    )
                    FilterChip(
                        selected = draft.authMode == SshAuthMode.PRIVATE_KEY,
                        onClick = {
                            draft = draft.copy(authMode = SshAuthMode.PRIVATE_KEY)
                            error = null
                        },
                        enabled = draft.enabled && !saving,
                        label = { Text(stringResource(R.string.ssh_tunnel_auth_key)) },
                    )
                }
                if (draft.authMode == SshAuthMode.PASSWORD) {
                    OutlinedTextField(
                        value = draft.password,
                        onValueChange = {
                            draft = draft.copy(password = it)
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_password)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = draft.privateKey,
                        onValueChange = {
                            draft = draft.copy(privateKey = it)
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_private_key)) },
                        enabled = draft.enabled && !saving,
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.passphrase,
                        onValueChange = {
                            draft = draft.copy(passphrase = it)
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_passphrase)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource(R.string.ssh_tunnel_remote_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.remoteHost,
                        onValueChange = {
                            draft = draft.copy(remoteHost = it.trim())
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_remote_host)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.remotePort.toString(),
                        onValueChange = { value ->
                            draft = draft.copy(remotePort = value.filter { it.isDigit() }.toIntOrNull() ?: 0)
                            error = null
                        },
                        label = { Text(stringResource(R.string.ssh_tunnel_remote_port)) },
                        singleLine = true,
                        enabled = draft.enabled && !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (draft.enabled) {
                    Text(
                        text = stringResource(R.string.ssh_tunnel_loopback_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activeOrigin == origin) {
                    val persisted = persistedConfig
                    if (persisted != null) {
                        SshTunnelStatus(
                            state = tunnelState,
                            persistedHost = persisted.host,
                            persistedPort = persisted.port,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = ::persist, enabled = !saving) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onClosed(saved) }, enabled = !saving) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

@Composable
private fun SshTunnelStatus(
    state: SshTunnelState,
    persistedHost: String,
    persistedPort: Int,
) {
    val message =
        when (state) {
            SshTunnelState.Disabled -> {
                stringResource(R.string.ssh_tunnel_status_off)
            }

            SshTunnelState.Connecting -> {
                stringResource(R.string.ssh_tunnel_status_connecting)
            }

            is SshTunnelState.Connected -> {
                stringResource(R.string.ssh_tunnel_status_connected, state.localPort)
            }

            is SshTunnelState.AwaitingHostKey -> {
                stringResource(R.string.ssh_tunnel_status_awaiting_key, state.keyType, state.fingerprint)
            }

            is SshTunnelState.Failed -> {
                stringResource(sshTunnelFailureMessage(state.reason))
            }
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state is SshTunnelState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        if (state is SshTunnelState.AwaitingHostKey) {
            Button(
                onClick = {
                    SshTunnelController.acceptPendingHostKey(
                        host = state.host,
                        keyType = state.keyType,
                        fingerprint = state.fingerprint,
                    )
                },
            ) {
                Text(stringResource(R.string.ssh_tunnel_trust_key))
            }
        }
        if (state is SshTunnelState.Failed && state.reason == SshTunnelFailure.HOST_KEY_CHANGED) {
            TextButton(
                onClick = {
                    SshTunnelController.forgetHostKey(
                        host = persistedHost,
                        port = persistedPort,
                    )
                },
            ) {
                Text(stringResource(R.string.ssh_tunnel_forget_key))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SshTunnelStatusConnectedPreview() {
    HermesControlTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            SshTunnelStatus(
                state = SshTunnelState.Connected(localPort = 54321),
                persistedHost = "ssh.example.com",
                persistedPort = 22,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SshTunnelStatusAwaitingKeyPreview() {
    HermesControlTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            SshTunnelStatus(
                state =
                    SshTunnelState.AwaitingHostKey(
                        host = "ssh.example.com",
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:abc123xyz456",
                    ),
                persistedHost = "ssh.example.com",
                persistedPort = 22,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SshTunnelStatusHostKeyChangedPreview() {
    HermesControlTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            SshTunnelStatus(
                state = SshTunnelState.Failed(SshTunnelFailure.HOST_KEY_CHANGED),
                persistedHost = "ssh.example.com",
                persistedPort = 2222,
            )
        }
    }
}
