package com.m57.hermescontrol.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.ws.ApprovalKind
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.ExpressiveFilledTonalButton as FilledTonalButton
import com.m57.hermescontrol.ui.common.ExpressiveTextButton as TextButton

/**
 * Folder (`access`) and site (`network`) approval card raised by a sandbox plugin.
 * Allow means once (until the sandbox is switched off); Always allow asks first; Deny may carry a reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SandboxApprovalCard(
    info: ApprovalInfo,
    onRespond: (action: String, reason: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmAlways by remember { mutableStateOf(false) }
    var denying by remember { mutableStateOf(false) }
    val isFolder = info.kind == ApprovalKind.ACCESS
    val choices = info.choices ?: listOf("once", "always", "deny")
    Column(
        modifier = modifier.fillMaxWidth().testTag("sandbox_approval_card"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(if (isFolder) R.string.approval_folder_title else R.string.approval_site_title),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (isFolder) {
            Text(
                text = info.path.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("sandbox_approval_target"),
            )
            info.access?.let { access ->
                Text(
                    text =
                        stringResource(
                            if (access ==
                                "read"
                            ) {
                                R.string.approval_access_read
                            } else {
                                R.string.approval_access_read_write
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            info.label?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = info.host.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("sandbox_approval_target"),
            )
        }
        info.risk?.let { risk ->
            Text(
                text = risk,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("sandbox_approval_risk"),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if ("deny" in choices) {
                FilledTonalButton(
                    onClick = { denying = true },
                    modifier = Modifier.testTag("deny_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) { Text(stringResource(R.string.approval_deny)) }
            }
            if ("always" in choices && info.allowPermanent != false) {
                FilledTonalButton(
                    onClick = { confirmAlways = true },
                    modifier = Modifier.testTag("always_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                ) { Text(stringResource(R.string.approval_always_allow)) }
            }
            if ("once" in choices) {
                FilledTonalButton(
                    onClick = { onRespond("once", null) },
                    modifier = Modifier.testTag("approve_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) { Text(stringResource(R.string.approval_allow)) }
            }
        }
    }
    if (confirmAlways) {
        AlertDialog(
            onDismissRequest = { confirmAlways = false },
            title = { Text(stringResource(R.string.approval_always_confirm_title)) },
            text = { Text(stringResource(R.string.approval_always_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAlways = false
                        onRespond("always", null)
                    },
                ) { Text(stringResource(R.string.approval_always_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlways = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (denying) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { denying = false },
            title = { Text(stringResource(R.string.approval_deny_title)) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.approval_deny_reason_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("deny_reason_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        denying = false
                        onRespond("deny", reason.trim().ifEmpty { null })
                    },
                ) { Text(stringResource(R.string.approval_deny_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { denying = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private val sampleFolder =
    ApprovalInfo(
        command = null,
        description = null,
        patternKeys = null,
        kind = ApprovalKind.ACCESS,
        path = "/home/user/projects/hermes-mobile/app/src",
        access = "read_write",
        risk = "The sandbox can change files in this folder.",
    )

private val sampleSite =
    ApprovalInfo(
        command = null,
        description = null,
        patternKeys = null,
        kind = ApprovalKind.NETWORK,
        host = "api.example.com",
        label = "Example API",
        risk = "The sandbox can send and receive data with this site.",
    )

@Composable
private fun SandboxApprovalSample(info: ApprovalInfo) {
    HermesControlTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SandboxApprovalCard(
                info = info,
                onRespond = { _, _ -> },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Light", widthDp = 360, heightDp = 260)
@Preview(name = "Dark", widthDp = 360, heightDp = 260, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text narrow", widthDp = 320, heightDp = 420, fontScale = 1.5f)
@Preview(name = "Arabic", widthDp = 360, heightDp = 260, locale = "ar")
@Composable
fun SandboxFolderApprovalPreview() = SandboxApprovalSample(sampleFolder)

@Preview(name = "Light", widthDp = 360, heightDp = 260)
@Preview(name = "Dark", widthDp = 360, heightDp = 260, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text narrow", widthDp = 320, heightDp = 420, fontScale = 1.5f)
@Preview(name = "Arabic", widthDp = 360, heightDp = 260, locale = "ar")
@Composable
fun SandboxSiteApprovalPreview() = SandboxApprovalSample(sampleSite)
