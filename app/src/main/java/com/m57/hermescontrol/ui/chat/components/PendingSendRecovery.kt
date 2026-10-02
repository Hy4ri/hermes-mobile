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
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.InlineAttachment
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState
import com.m57.hermescontrol.ui.chat.needsRecovery

/** #1427: a compact recovery entry keeps uncertain delivery visible without a floating queue panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingSendRecovery(
    sends: List<PendingSend>,
    canSend: Boolean,
    mainTurnBusy: Boolean,
    onSendAgain: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onRemoveAcknowledged: (PendingSend) -> Unit,
    onOpenAttachment: (Attachment) -> Unit = {},
    onImageClick: (ImageViewerModel) -> Unit = {},
) {
    val recoverySends = sends.filter { it.needsRecovery }
    if (recoverySends.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var retryId by remember { mutableStateOf<String?>(null) }
    var discardId by remember { mutableStateOf<String?>(null) }
    var acknowledgeId by remember { mutableStateOf<String?>(null) }
    var removeSnapshot by remember { mutableStateOf<PendingSend?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submissionInFlight = sends.any { it.state == PendingSendState.SENDING }
    TextButton(
        onClick = {
            keyboard?.hide()
            open = true
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pending_send_recovery"),
    ) {
        Text(pluralStringResource(R.plurals.chat_pending_count, recoverySends.size, recoverySends.size))
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.chat_pending_sends),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(recoverySends, key = { it.id }) { send ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(send.text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(
                                when (send.state) {
                                    PendingSendState.QUEUED -> {
                                        R.string.chat_pending_queued
                                    }

                                    PendingSendState.PARKED -> {
                                        R.string.chat_pending_parked
                                    }

                                    PendingSendState.SENDING -> {
                                        R.string.chat_pending_sending
                                    }

                                    PendingSendState.ACCEPTED -> {
                                        R.string.chat_pending_accepted
                                    }

                                    PendingSendState.UNKNOWN -> {
                                        if (send.userOrderingReleased) {
                                            R.string.chat_pending_unknown_released
                                        } else {
                                            R.string.chat_pending_unknown
                                        }
                                    }

                                    PendingSendState.REJECTED -> {
                                        R.string.chat_pending_rejected
                                    }
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        send.attachments.forEach { attachment ->
                            if (attachment.isImage || attachment.isAudio || attachment.isVideo) {
                                Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            InlineAttachment(
                                attachment = attachment,
                                textColor = MaterialTheme.colorScheme.onSurface,
                                onOpen = onOpenAttachment,
                                onSave = {},
                                savingPath = null,
                                openingPath = null,
                                canSave = false,
                                onImageClick = onImageClick,
                            )
                        }
                        if (send.state == PendingSendState.UNKNOWN) {
                            Text(
                                stringResource(R.string.chat_pending_delivery_unverified),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    if (send.userOrderingReleased) {
                                        removeSnapshot = send
                                    } else {
                                        acknowledgeId =
                                            send.id
                                    }
                                },
                                enabled =
                                    send.userOrderingReleased ||
                                        (canSend && !mainTurnBusy && !submissionInFlight),
                                modifier =
                                    Modifier.testTag(
                                        if (send.userOrderingReleased) {
                                            "pending_remove_${send.id}"
                                        } else {
                                            "pending_acknowledge_${send.id}"
                                        },
                                    ),
                            ) {
                                Text(
                                    stringResource(
                                        if (send.userOrderingReleased) {
                                            R.string.chat_pending_remove
                                        } else {
                                            R.string.chat_pending_continue_queue
                                        },
                                    ),
                                )
                            }
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    if (mainTurnBusy ||
                                        send.state == PendingSendState.ACCEPTED ||
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
                            if (send.state != PendingSendState.UNKNOWN) {
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
    }
    recoverySends.firstOrNull { it.id == retryId }?.let { send ->
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
    recoverySends.firstOrNull { it.id == acknowledgeId }?.let { send ->
        AlertDialog(
            onDismissRequest = { acknowledgeId = null },
            title = { Text(stringResource(R.string.chat_pending_continue_title)) },
            text = { Text(stringResource(R.string.chat_pending_continue_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        acknowledgeId = null
                        onAcknowledge(send.id)
                    },
                    enabled = canSend && !mainTurnBusy && !submissionInFlight,
                    modifier = Modifier.testTag("pending_acknowledge_confirm"),
                ) { Text(stringResource(R.string.chat_pending_continue_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { acknowledgeId = null }) { Text(stringResource(R.string.system_confirm_cancel)) }
            },
        )
    }
    removeSnapshot?.let { snapshot ->
        val current = recoverySends.firstOrNull { it.id == snapshot.id }
        AlertDialog(
            onDismissRequest = { removeSnapshot = null },
            title = { Text(stringResource(R.string.chat_pending_remove_title)) },
            text = { Text(stringResource(R.string.chat_pending_remove_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeSnapshot = null
                        onRemoveAcknowledged(snapshot)
                    },
                    enabled =
                        current == snapshot &&
                            snapshot.state == PendingSendState.UNKNOWN &&
                            snapshot.userOrderingReleased,
                    modifier = Modifier.testTag("pending_remove_confirm"),
                ) { Text(stringResource(R.string.chat_pending_remove_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeSnapshot = null },
                ) { Text(stringResource(R.string.system_confirm_cancel)) }
            },
        )
    }
    recoverySends.firstOrNull { it.id == discardId && it.state != PendingSendState.UNKNOWN }?.let { send ->
        AlertDialog(
            onDismissRequest = { discardId = null },
            title = { Text(stringResource(R.string.chat_pending_discard)) },
            text = { Text(stringResource(R.string.chat_pending_discard_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardId = null
                        if (recoverySends.any { it.id == send.id && it.state != PendingSendState.UNKNOWN }) {
                            onDiscard(send.id)
                        }
                    },
                    enabled = send.state != PendingSendState.SENDING && send.state != PendingSendState.UNKNOWN,
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
