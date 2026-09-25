package com.m57.hermescontrol.ui.chat

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
