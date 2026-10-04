package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.R

/** Shared presentation policy; receipt uncertainty is not runtime activity. */
internal fun pendingSendActivityWarning(
    mainTurnBusy: Boolean,
    sends: List<PendingSend>,
): Int? =
    if (mainTurnBusy && sends.any { it.state != PendingSendState.SENDING && it.state != PendingSendState.ACCEPTED }) {
        R.string.chat_pending_send_now_warning
    } else {
        null
    }

/** Presentation only: retain queued messages internally for persistence and receipt matching. */
internal fun messagesWithoutUnsentQueue(
    messages: List<ChatMessage>,
    pendingSends: List<PendingSend>,
): List<ChatMessage> {
    val waitingIds =
        pendingSends
            .filter { it.state == PendingSendState.QUEUED || it.state == PendingSendState.PARKED }
            .map { it.id }
            .toSet()
    if (waitingIds.isEmpty()) return messages
    return messages.filterNot {
        it.role == MessageRole.USER && it.id in waitingIds && it.canonicalRestId == null
    }
}
