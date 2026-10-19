package com.m57.hermescontrol.ui.sessions.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import com.m57.hermescontrol.ui.sessions.SessionsViewModel
import com.m57.hermescontrol.ui.sessions.TransferKind

/**
 * Hosts the privacy warning, the Storage Access Framework save picker, and the system
 * share chooser for transcript sharing and JSON export (issue #1610).
 */
@Composable
fun SessionTransferHost(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
) {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) {
                viewModel.cancelExportPicker()
            } else {
                viewModel.writeExport { context.contentResolver.openOutputStream(uri, "wt") }
            }
        }

    state.pendingTransfer?.let { transfer ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text(stringResource(R.string.sessions_transfer_privacy_title)) },
            text = {
                Text(
                    stringResource(
                        if (transfer.kind == TransferKind.EXPORT_JSON) {
                            R.string.sessions_transfer_privacy_export
                        } else {
                            R.string.sessions_transfer_privacy_share
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmTransfer) {
                    Text(stringResource(R.string.sessions_transfer_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTransfer) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val pickerRequest = state.exportPicker
    LaunchedEffect(pickerRequest) {
        if (pickerRequest != null) picker.launch(pickerRequest.suggestedName)
    }

    val share = state.shareRequest
    val chooserTitle = stringResource(R.string.sessions_transfer_chooser_title)
    LaunchedEffect(share?.id) {
        if (share != null) {
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, share.title)
                    putExtra(Intent.EXTRA_TEXT, share.text)
                }
            viewModel.consumeShareRequest(share.id)
            runCatching { context.startActivity(Intent.createChooser(send, chooserTitle)) }
        }
    }
}
