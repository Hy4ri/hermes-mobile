package com.m57.hermescontrol.ui.authlogin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import okhttp3.HttpUrl

@Composable
internal fun CertificatePromptDialog(
    state: CertificatePromptState,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val origin = state.origin ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mtls_prompt_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text(origin.certificateAddress(), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.mtls_prompt_help))
                Text(state.alias ?: stringResource(R.string.mtls_none))
                TextButton(onClick = onSelect, enabled = !state.choosing && !state.saving) {
                    Text(stringResource(if (state.choosing) R.string.mtls_choosing else R.string.mtls_select))
                }
                if (state.selectionFailed) {
                    Text(stringResource(R.string.mtls_select_error), color = MaterialTheme.colorScheme.error)
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = state.alias != null && !state.choosing && !state.saving) {
                Text(stringResource(if (state.saving) R.string.mtls_prompt_verifying else R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun CertificateSavedNotice(origin: HttpUrl?) {
    if (origin != null) {
        Text(
            stringResource(R.string.mtls_prompt_saved, origin.certificateAddress()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun HttpUrl.certificateAddress(): String = "${if (':' in host) "[$host]" else host}:$port"
