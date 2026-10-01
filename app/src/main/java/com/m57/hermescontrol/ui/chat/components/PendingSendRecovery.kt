package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState

/** #1427: a compact recovery entry keeps uncertain delivery visible without a floating queue panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingSendRecovery(
    sends: List<PendingSend>,
    canSend: Boolean,
    mainTurnBusy: Boolean,
    onSendAgain: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    if (sends.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var retryId by remember { mutableStateOf<String?>(null) }
    var discardId by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submissionInFlight = sends.any { it.state == PendingSendState.SENDING }
    TextButton(
        onClick = {
            keyboard?.hide()
            open = true
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pending_send_recovery"),
    ) {
        Text(pluralStringResource(R.plurals.chat_pending_count, sends.size, sends.size))
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.chat_pending_sends),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(sends, key = { it.id }) { send ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(send.text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(
                                when (send.state) {
                                    PendingSendState.QUEUED -> R.string.chat_pending_queued
                                    PendingSendState.PARKED -> R.string.chat_pending_parked
                                    PendingSendState.SENDING -> R.string.chat_pending_sending
                                    PendingSendState.ACCEPTED -> R.string.chat_pending_accepted
                                    PendingSendState.UNKNOWN -> R.string.chat_pending_unknown
                                    PendingSendState.REJECTED -> R.string.chat_pending_rejected
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (send.attachments.isNotEmpty()) {
                            Text(stringResource(R.string.chat_pending_attachments))
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    if (mainTurnBusy || send.state == PendingSendState.ACCEPTED ||
                                        send.state == PendingSendState.UNKNOWN
                                    ) {
                                        retryId = send.id
                                    } else {
                                        onSendAgain(send.id)
                                    }
                                },
                                enabled = canSend && !submissionInFlight && !send.requiresAttachmentRecovery,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("pending_retry_${send.id}"),
                            ) {
                                Text(
                                    stringResource(
                                        if (send.state == PendingSendState.ACCEPTED ||
                                            send.state == PendingSendState.UNKNOWN
                                        ) {
                                            R.string.chat_pending_send_again
                                        } else {
                                            R.string.chat_pending_send_now
                                        },
                                    ),
                                )
                            }
                            TextButton(
                                onClick = { discardId = send.id },
                                enabled = send.state != PendingSendState.SENDING,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("pending_discard_${send.id}"),
                            ) {
                                Text(stringResource(R.string.chat_pending_discard))
                            }
                        }
                    }
                }
            }
        }
    }
    sends.firstOrNull { it.id == retryId }?.let { send ->
        AlertDialog(
            onDismissRequest = { retryId = null },
            title = { Text(stringResource(R.string.chat_pending_send_again)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(send.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    if (send.state == PendingSendState.ACCEPTED || send.state == PendingSendState.UNKNOWN) {
                        Text(stringResource(R.string.chat_pending_unknown))
                    }
                    if (mainTurnBusy) Text(stringResource(R.string.chat_pending_send_now_warning))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    retryId = null
                    onSendAgain(send.id)
                }, enabled = canSend && !submissionInFlight, modifier = Modifier.testTag("pending_retry_confirm")) {
                    Text(stringResource(R.string.chat_pending_send_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { retryId = null }) { Text(stringResource(R.string.system_confirm_cancel)) }
            },
        )
    }
    sends.firstOrNull { it.id == discardId }?.let { send ->
        AlertDialog(
            onDismissRequest = { discardId = null },
            title = { Text(stringResource(R.string.chat_pending_discard)) },
            text = { Text(stringResource(R.string.chat_pending_discard_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardId = null
                        onDiscard(send.id)
                    },
                    enabled = send.state != PendingSendState.SENDING,
                    modifier =
                        Modifier.testTag(
                            "pending_discard_confirm",
                        ),
                ) {
                    Text(stringResource(R.string.chat_pending_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardId = null }) { Text(stringResource(R.string.system_confirm_cancel)) }
            },
        )
    }
}
