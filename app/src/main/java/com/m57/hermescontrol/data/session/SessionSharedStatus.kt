package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus

/**
 * How recent `last_activity_at` must be, in seconds, for the shared activity description to mean a turn
 * is running right now. A live turn refreshes its description far more often than this; the backend keeps
 * `last_activity_at` after a turn ends, so an old timestamp must not read as work in progress.
 */
const val SHARED_ACTIVITY_FRESH_SECONDS = 180

/**
 * Badge for [row] from the fields of the shared session database, which every surface writes (desktop,
 * Signal, other gateways); `session.active_list` only knows this gateway's own turns.
 *
 * WORKING: a non-blank activity description whose `last_activity_at` is fresh. DONE: the backend marks the
 * row unread (desktop paints from the same flag; a row with no read watermark is read). [localReadAt] is
 * this phone's optimistic read time: it hides a server unread flag older than that read, so a list that
 * was fetched before the PATCH landed cannot bring the badge back. A backend that sends none of these
 * fields yields null, as before.
 */
fun sharedStatusOf(
    row: SessionInfo,
    localReadAt: Double?,
    nowSeconds: Double,
): SessionLiveStatus? {
    val activityAt = row.last_activity_at
    if (!row.last_activity_description.isNullOrBlank() &&
        activityAt != null &&
        nowSeconds - activityAt < SHARED_ACTIVITY_FRESH_SECONDS
    ) {
        return SessionLiveStatus.WORKING
    }
    if (row.unread != true) return null
    val lastActive = row.last_active ?: row.started_at
    if (localReadAt != null && (lastActive == null || localReadAt >= lastActive)) return null
    return SessionLiveStatus.DONE
}
