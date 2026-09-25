package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState

@Composable
fun PendingSendPanel(
    sends: List<PendingSend>,
    mainTurnBusy: Boolean,
    onSendNow: (String) -> Unit,
) {
    // Delivery receipts stay internal; only queued work and recovery need UI.
    val visibleSends = sends.filter { it.state != PendingSendState.SENDING && it.state != PendingSendState.ACCEPTED }
    if (visibleSends.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(stringResource(R.string.chat_pending_sends), style = MaterialTheme.typography.titleSmall)
            if (mainTurnBusy || visibleSends.any { it.state == PendingSendState.UNKNOWN }) {
                Text(
                    stringResource(R.string.chat_pending_send_now_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = 170.dp)) {
                items(visibleSends, key = { it.id }) { send ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                send.text.ifBlank { stringResource(R.string.chat_pending_attachments) },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text =
                                    when (send.state) {
                                        PendingSendState.QUEUED -> stringResource(R.string.chat_pending_queued)
                                        PendingSendState.PARKED -> stringResource(R.string.chat_pending_parked)
                                        PendingSendState.SENDING -> stringResource(R.string.chat_pending_sending)
                                        PendingSendState.ACCEPTED -> stringResource(R.string.chat_pending_accepted)
                                        PendingSendState.UNKNOWN -> stringResource(R.string.chat_pending_unknown)
                                        PendingSendState.REJECTED -> stringResource(R.string.chat_pending_rejected)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (send.state in
                            setOf(
                                PendingSendState.QUEUED,
                                PendingSendState.PARKED,
                                PendingSendState.UNKNOWN,
                                PendingSendState.REJECTED,
                                PendingSendState.ACCEPTED,
                            )
                        ) {
                            TextButton(onClick = { onSendNow(send.id) }) {
                                Text(
                                    stringResource(
                                        if (mainTurnBusy) {
                                            R.string.chat_busy_stop_and_send
                                        } else if (send.state in
                                            setOf(
                                                PendingSendState.UNKNOWN,
                                                PendingSendState.ACCEPTED,
                                            )
                                        ) {
                                            R.string.chat_pending_send_again
                                        } else {
                                            R.string.chat_pending_send_now
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
