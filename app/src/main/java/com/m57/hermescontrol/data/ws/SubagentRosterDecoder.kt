package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.LiveWorkCounts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Decodes a `subagent.roster` result (`{sessions: [{session_id, subagents, background}]}`, #387) into
 * counts keyed by stored session id. Rows without an id or without live work are dropped. Returns null
 * when the reply has no `sessions` array, so a malformed reply never reads as "no live work".
 */
object SubagentRosterDecoder {
    fun decode(raw: Any?): Map<String, LiveWorkCounts>? {
        val element = SessionLiveStatusDecoder.anyToJsonElement(raw)
        val sessions = (element as? JsonObject)?.get("sessions") as? JsonArray ?: return null
        val counts = mutableMapOf<String, LiveWorkCounts>()
        for (row in sessions) {
            val obj = row as? JsonObject ?: continue
            val id = (obj["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (id.isNullOrEmpty()) continue
            val entry = LiveWorkCounts(subagents = obj.count("subagents"), background = obj.count("background"))
            if (!entry.isEmpty) counts[id] = entry
        }
        return counts
    }

    private fun JsonObject.count(key: String): Int = ((get(key) as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
}
