package com.m57.hermescontrol.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.CaReference
import com.m57.hermescontrol.data.remote.CertificateEditSession
import com.m57.hermescontrol.data.remote.ClientCertificates
import com.m57.hermescontrol.data.remote.ServerCaRestrictions
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
internal fun ClientCertificatesPage(onBack: () -> Unit) {
    val bindings by ClientCertificates.state.collectAsStateWithLifecycle()
    val caBindings by ServerCaRestrictions.state.collectAsStateWithLifecycle()
    val mixed =
        (
            bindings.map { CertificateConfiguration(it.key, false, it.value) } +
                caBindings.map { CertificateConfiguration(it.key, true, it.value) }
        ).sortedWith(compareBy({ it.url }, { it.tls }))
    var editingTls by remember { mutableStateOf(true) }
    var deletingTls by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf<HttpUrl?>(null) }
    var deleting by remember { mutableStateOf<HttpUrl?>(null) }
    var deleteExpected by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var deleteError by remember { mutableStateOf(false) }
    var deleteSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current

    HermesScaffold(
        title = { Text(stringResource(R.string.tls_mtls_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(stringResource(R.string.tls_mtls_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                Text(
                    pluralStringResource(R.plurals.mtls_saved_configurations, mixed.size, mixed.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(spacing.sm))
                if (mixed.isEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 144.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.mtls_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                mixed.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val url = entry.url.toHttpUrl()
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${url.certificateAddress()} · ${if (entry.tls) "TLS" else "mTLS"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (entry.tls) {
                                        caLabel(entry.certificate)
                                    } else {
                                        entry.certificate
                                            ?: stringResource(R.string.mtls_none)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                previous = url
                                editingTls = entry.tls
                                editing = true
                            }) {
                                Icon(Icons.Filled.Edit, stringResource(R.string.mtls_edit), Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                deleting = url
                                deletingTls = entry.tls
                                deleteExpected = if (entry.tls) caBindings else bindings
                                deleteError = false
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    stringResource(R.string.mtls_delete),
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        previous = null
                        editingTls = true
                        editing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(spacing.sm))
                    Text(stringResource(R.string.mtls_add))
                }
            }
        }
    }
    if (editing) CertificateBindingDialog(previous, bindings, caBindings, editingTls) { editing = false }
    deleting?.let { url ->
        AlertDialog(
            onDismissRequest = { if (!deleteSaving) deleting = null },
            title = { Text(stringResource(R.string.mtls_delete_title)) },
            text = {
                Text(
                    if (deleteError) {
                        stringResource(R.string.mtls_save_error)
                    } else {
                        stringResource(
                            R.string.tls_mtls_delete_help,
                            url.certificateAddress(),
                            if (deletingTls) "TLS" else "mTLS",
                        )
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !deleteSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        deleteSaving = true
                        scope.launch {
                            val saved =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (deletingTls) {
                                            ServerCaRestrictions.save(url, null, null, deleteExpected)
                                        } else {
                                            ClientCertificates.save(url, null, null, deleteExpected)
                                        }
                                    }.isSuccess
                                }
                            deleteSaving = false
                            if (saved) deleting = null else deleteError = true
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !deleteSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CertificateBindingDialog(
    previous: HttpUrl?,
    bindings: Map<String, String?>,
    caBindings: Map<String, String?>,
    initialTls: Boolean,
    onDismiss: () -> Unit,
) {
    var tls by remember { mutableStateOf(initialTls) }
    val clientExpected = remember { bindings }
    val caExpected = remember { caBindings }
    val expected = if (tls) caExpected else clientExpected
    val initial =
        remember {
            CertificateBindingDraft(
                previous?.host.orEmpty(),
                (previous?.port ?: 443).toString(),
                previous?.let {
                    expected[it.toString()]
                },
            )
        }
    var draft by remember { mutableStateOf(initial) }
    val session = remember { CertificateEditSession() }
    var choosing by remember { mutableStateOf(false) }
    var pickingCa by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var hostEdited by remember { mutableStateOf(false) }
    var portEdited by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val origin = draft.origin
    val duplicate = draft.duplicate(previous, expected)
    val requestDismiss = {
        if (!saving) {
            session.invalidate()
            choosing = false
            if (draft.changedFrom(initial) || tls != initialTls) discard = true else onDismiss()
        }
    }
    DisposableEffect(session) { onDispose { session.close() } }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(stringResource(if (previous == null) R.string.mtls_add else R.string.mtls_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text(stringResource(R.string.tls_mtls_type), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(true, false).forEach { value ->
                        val label = stringResource(if (value) R.string.tls_type_tls else R.string.tls_type_mtls)
                        RadioButton(
                            modifier = Modifier.semantics { contentDescription = label },
                            selected = tls == value,
                            enabled = previous == null && !saving && !choosing,
                            onClick = {
                                tls = value
                                draft = draft.copy(alias = null)
                                session.invalidate()
                                error = null
                            },
                        )
                        Text(if (value) "TLS" else "mTLS")
                    }
                }
                Text(stringResource(if (tls) R.string.tls_restriction_help else R.string.mtls_help))
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = {
                        draft = draft.copy(host = it)
                        hostEdited = true
                        session.invalidate()
                        choosing = false
                        error = null
                    },
                    label = { Text(stringResource(R.string.mtls_host)) },
                    singleLine = true,
                    enabled = !saving,
                    isError = (hostEdited && !draft.validHost) || duplicate,
                    supportingText =
                        if (duplicate || (hostEdited && !draft.validHost)) {
                            {
                                Text(
                                    stringResource(
                                        if (duplicate) R.string.mtls_duplicate else R.string.mtls_invalid_host,
                                    ),
                                )
                            }
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = {
                        draft = draft.copy(port = it)
                        portEdited = true
                        session.invalidate()
                        choosing = false
                        error = null
                    },
                    label = { Text(stringResource(R.string.mtls_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !saving,
                    isError = portEdited && !draft.validPort,
                    supportingText =
                        if (portEdited && !draft.validPort) {
                            { Text(stringResource(R.string.mtls_invalid_port)) }
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(if (tls) R.string.tls_ca_certificate else R.string.mtls_certificate),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tls) caLabel(draft.alias) else draft.alias ?: stringResource(R.string.mtls_none),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = (tls || activity != null) && origin != null && !duplicate && !choosing && !saving,
                        onClick = {
                            if (tls) {
                                pickingCa = true
                            } else {
                                val token = session.beginSelection()
                                choosing = true
                                error = null
                                ClientCertificates.select(
                                    requireNotNull(activity),
                                    requireNotNull(origin).url,
                                    draft.alias,
                                ) { value, ok ->
                                    if (session.accept(token)) {
                                        choosing = false
                                        if (!ok) {
                                            error = R.string.mtls_select_error
                                        } else if (value !=
                                            null
                                        ) {
                                            draft = draft.copy(alias = value, certificateReselected = true)
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                when {
                                    choosing -> R.string.mtls_choosing
                                    draft.alias != null -> R.string.mtls_replace
                                    else -> R.string.mtls_select
                                },
                            ),
                        )
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = draft.canSave(previous, expected) && !choosing && !saving,
                onClick = {
                    saving = true
                    session.invalidate()
                    scope.launch {
                        val saved =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    if (tls) {
                                        ServerCaRestrictions.save(
                                            previous,
                                            requireNotNull(origin).url,
                                            CaReference.decode(requireNotNull(draft.alias)),
                                            expected,
                                        )
                                    } else {
                                        ClientCertificates.save(
                                            previous,
                                            requireNotNull(origin).url,
                                            draft.alias,
                                            expected,
                                        )
                                    }
                                }.isSuccess
                            }
                        saving = false
                        if (saved) onDismiss() else error = R.string.mtls_save_error
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = requestDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (pickingCa) {
        AndroidCaPicker(
            onDismiss = { pickingCa = false },
            onSelect = { reference ->
                pickingCa = false
                draft = draft.copy(alias = reference.encode(), certificateReselected = true)
                error = null
            },
        )
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.mtls_discard_title)) },
            text = { Text(stringResource(R.string.mtls_discard_help)) },
            confirmButton = {
                Button(
                    onClick = {
                        session.close()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.mtls_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.mtls_continue_editing)) }
            },
        )
    }
}

private fun HttpUrl.certificateAddress(): String = "${if (':' in host) "[$host]" else host}:$port"

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun caLabel(value: String?): String =
    value?.let { runCatching { CaReference.decode(it).subject }.getOrNull() }
        ?: stringResource(R.string.mtls_none)

private data class CertificateConfiguration(
    val url: String,
    val tls: Boolean,
    val certificate: String?,
)
