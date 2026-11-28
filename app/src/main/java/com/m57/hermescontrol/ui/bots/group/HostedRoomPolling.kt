package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.WsEvent

/** Poll period while the backend pushes `groups.changed`: a lost push still lands within this. */
internal const val HOSTED_BACKSTOP_POLL_MS = 15_000L

/** Poll period on backends without change events, and the retry period after a failed read. */
internal const val HOSTED_FAST_POLL_MS = 2_000L

private const val SEEN_BROADCASTS_MAX = 64

/** Next wait before re-reading the open room. */
internal fun hostedPollIntervalMs(
    backendPushes: Boolean,
    lastReadFailed: Boolean,
): Long = if (backendPushes && !lastReadFailed) HOSTED_BACKSTOP_POLL_MS else HOSTED_FAST_POLL_MS

/** Decides which `groups.changed` frames trigger a read of the open room. */
internal class GroupsChangedGate {
    private val seen = LinkedHashSet<String>()

    fun shouldRead(
        event: WsEvent.GroupsChanged,
        openRoomId: String?,
    ): Boolean {
        val id = event.broadcastId
        if (id != null) {
            if (!seen.add(id)) return false
            if (seen.size > SEEN_BROADCASTS_MAX) seen.remove(seen.first())
        }
        val rooms = event.roomIds ?: return true
        return openRoomId != null && openRoomId in rooms
    }
}
