package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.AndroidCaCertificate
import com.m57.hermescontrol.data.remote.CaReference
import com.m57.hermescontrol.data.remote.CaSource
import com.m57.hermescontrol.data.remote.ServerCaRestrictions
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat

@Composable
internal fun AndroidCaPicker(
    onDismiss: () -> Unit,
    onSelect: (CaReference) -> Unit,
) {
    var source by remember { mutableStateOf(CaSource.SYSTEM) }
    var certificates by remember { mutableStateOf<List<AndroidCaCertificate>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AndroidCaCertificate?>(null) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { ServerCaRestrictions.store.list() } }
        certificates = result.getOrDefault(emptyList())
        failed = result.isFailure
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tls_ca_picker)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaSource.entries.forEach { value ->
                        val label =
                            stringResource(
                                if (value == CaSource.SYSTEM) R.string.tls_ca_system else R.string.tls_ca_user,
                            )
                        RadioButton(
                            modifier = Modifier.semantics { contentDescription = label },
                            selected = source == value,
                            onClick = {
                                source = value
                                selected = null
                            },
                        )
                        Text(
                            stringResource(
                                if (value ==
                                    CaSource.SYSTEM
                                ) {
                                    R.string.tls_ca_system
                                } else {
                                    R.string.tls_ca_user
                                },
                            ),
                        )
                    }
                }
                when {
                    failed -> {
                        Text(stringResource(R.string.tls_ca_load_error), color = MaterialTheme.colorScheme.error)
                    }

                    certificates == null -> {
                        Text(stringResource(R.string.tls_ca_loading))
                    }

                    certificates!!.none { it.reference.source == source } -> {
                        Text(stringResource(R.string.tls_ca_empty))
                    }

                    else -> {
                        AndroidCaCertificateList(
                            certificates = certificates!!.filter { it.reference.source == source },
                            onClick = { selected = it },
                        )
                    }
                }
                selected?.let { ca ->
                    // Details remain scrollable at large font sizes via a separate dialog below.
                    Text(ca.reference.subject, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    selected?.let { ca ->
        val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.tls_ca_details)) },
            text = { CaDetails(ca, dateFormat) },
            confirmButton = {
                Button(onClick = { onSelect(ca.reference) }) { Text(stringResource(R.string.mtls_select)) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** #1518: retain the original DN buttons; only adjacent entries get a decorative separator. */
@Composable
internal fun AndroidCaCertificateList(
    certificates: List<AndroidCaCertificate>,
    onClick: (AndroidCaCertificate) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        itemsIndexed(certificates, key = { _, ca -> ca.reference.alias }) { index, ca ->
            TextButton(
                onClick = { onClick(ca) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(ca.reference.subject, modifier = Modifier.fillMaxWidth())
            }
            if (index < certificates.lastIndex) {
                HorizontalDivider(
                    modifier =
                        Modifier
                            .padding(
                                start = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(layoutDirection),
                                end = ButtonDefaults.TextButtonContentPadding.calculateEndPadding(layoutDirection),
                            ).testTag("ca-certificate-divider"),
                    thickness = 0.5.dp,
                    color = caCertificateDividerColor(MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}

internal fun caCertificateDividerColor(outline: Color): Color = outline.copy(alpha = 0.24f)

@Composable
private fun CaDetails(
    ca: AndroidCaCertificate,
    dateFormat: DateFormat,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        Text(
            stringResource(
                R.string.tls_ca_source,
                stringResource(
                    if (ca.reference.source ==
                        CaSource.SYSTEM
                    ) {
                        R.string.tls_ca_system
                    } else {
                        R.string.tls_ca_user
                    },
                ),
            ),
        )
        Text(stringResource(R.string.tls_ca_subject, ca.reference.subject))
        Text(stringResource(R.string.tls_ca_issuer, ca.certificate.issuerX500Principal.name))
        Text(
            stringResource(
                R.string.tls_ca_validity,
                dateFormat.format(ca.certificate.notBefore),
                dateFormat.format(ca.certificate.notAfter),
            ),
        )
        Text(
            stringResource(
                R.string.tls_ca_fingerprint,
                ca.reference.sha256
                    .chunked(2)
                    .joinToString(":"),
            ),
        )
    }
}
