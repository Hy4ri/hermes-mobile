package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One authoritative row from `mcp.servers.status`: the backend's cached view of a configured
 * server's runtime state (#419). `status` is one of connected|disabled|connecting|failed|lazy|
 * configured; [needsAuth] is set when the backend parked the server on a rejected OAuth credential
 * (older backends omit it, so the client treats a missing key as false).
 */
data class McpRuntimeStatus(
    val name: String,
    val transport: String? = null,
    val tools: Int = 0,
    val connected: Boolean = false,
    val disabled: Boolean = false,
    val status: String? = null,
    val source: String? = null,
    val plugin: String? = null,
    val needsAuth: Boolean = false,
)

/**
 * Decodes a `mcp.servers.status` result (`{servers: [...], checked_at: int}`) into rows keyed by
 * server name. Non-object entries and rows without a name are skipped; `needs_auth` defaults false
 * when absent. Returns null when the reply has no `servers` array, so a malformed reply never
 * reads as "no runtime evidence".
 */
object McpRuntimeStatusDecoder {
    fun decode(raw: Any?): Map<String, McpRuntimeStatus>? {
        val element = SessionLiveStatusDecoder.anyToJsonElement(raw)
        val servers = (element as? JsonObject)?.get("servers") as? JsonArray ?: return null
        val rows = mutableMapOf<String, McpRuntimeStatus>()
        for (entry in servers) {
            val obj = entry as? JsonObject ?: continue
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
            if (name.isEmpty()) continue
            rows[name] =
                McpRuntimeStatus(
                    name = name,
                    transport = (obj["transport"] as? JsonPrimitive).stringOrNull(),
                    tools = (obj["tools"] as? JsonPrimitive)?.intOrNull ?: 0,
                    connected = (obj["connected"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    disabled = (obj["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    status = (obj["status"] as? JsonPrimitive).stringOrNull(),
                    source = (obj["source"] as? JsonPrimitive).stringOrNull(),
                    plugin = (obj["plugin"] as? JsonPrimitive).stringOrNull(),
                    needsAuth = (obj["needs_auth"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
        }
        return rows
    }
}

private fun JsonPrimitive?.stringOrNull(): String? =
    this?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
