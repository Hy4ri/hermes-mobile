package com.m57.hermescontrol.ui.chat

/**
 * `message.complete` `persisted_turn` (hermes-agent `PersistedTurn`, #1285). Only the anchors Mobile
 * can bind without inference are kept; `row_ids` for intermediate rows would need positional
 * guessing. A missing id is unproven, never a rejection, and only [complete] permits retiring the
 * whole local turn. Row ids are scoped to the owning profile store.
 */
internal data class PersistedTurn(
    val complete: Boolean,
    val userRowId: Long? = null,
    val finalAssistantRowId: Long? = null,
)

/** Decoded JSON numbers may arrive as Int, Long, or Double; booleans and non-positive ids are not ids. */
internal fun positiveRowId(value: Any?): Long? =
    when (value) {
        is Int, is Long -> (value as Number).toLong()
        is Double -> value.takeIf { it % 1.0 == 0.0 }?.toLong()
        else -> null
    }?.takeIf { it > 0L }

internal fun parsePersistedTurn(payload: Map<*, *>?): PersistedTurn? {
    val raw = payload?.get("persisted_turn") as? Map<*, *> ?: return null
    return PersistedTurn(
        complete = raw["complete"] == true,
        userRowId = positiveRowId(raw["user_row_id"]),
        finalAssistantRowId = positiveRowId(raw["final_assistant_row_id"]),
    )
}
