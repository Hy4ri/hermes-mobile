package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-authority edits. The authority serves archive, delete and workspace moves as
 * `session.mutate`, a revision-fenced, retry-idempotent edit (`{session_id, request_id,
 * expected_revision, operation, payload}`, plus `expected_generation` for fenced edits). This class
 * keeps what that needs: the newest revision of each session, and the request a lost reply left
 * unanswered, so a retry re-sends the same `request_id` (the owner then replays its receipt instead of
 * applying the edit twice). Mirrors Desktop's `canonical-protocol.ts` retained mutations.
 */
internal class SessionMutations {
    private val revisions = ConcurrentHashMap<String, Int>()
    private val retained = ConcurrentHashMap<String, JsonObject>()
    private val inFlight = ConcurrentHashMap<String, JsonObject>()

    fun clear() {
        revisions.clear()
        retained.clear()
        inFlight.clear()
    }

    /** A `session.info` event states the revision the next edit must present; `session.updated` only moves it forward. */
    fun adoptEvent(
        type: String?,
        sessionId: String,
        payload: JsonObject?,
    ) {
        val revision = (payload?.get("revision") as? JsonPrimitive)?.intOrNull ?: return
        when (type) {
            "session.info" -> revisions[sessionId] = revision
            "session.updated" -> adopt(sessionId, revision)
        }
    }

    /** The `session.mutate` params for a legacy edit, or null when its fences are not known yet. */
    fun prepare(
        method: String,
        params: JsonObject,
        generation: Int?,
    ): JsonObject? {
        val (sessionKey, operation, payload) =
            when (method) {
                WsMethods.SESSION_ARCHIVE -> {
                    val archived = params["archived"] ?: JsonPrimitive(true)
                    Triple(params["session_id"], "archive", JsonObject(mapOf("archived" to archived)))
                }

                WsMethods.SESSION_COMPRESS -> {
                    val payload =
                        buildMap {
                            params["focus_topic"]?.let { put("focus", it) }
                            listOf("preview", "partial", "keep_last").forEach { key ->
                                params[key]?.let { put(key, it) }
                            }
                        }
                    Triple(params["session_id"], "compress", JsonObject(payload))
                }

                WsMethods.SESSION_DELETE -> {
                    Triple(params["session_id"], "delete", JsonObject(emptyMap()))
                }

                WsMethods.SESSION_WORKSPACE_MOVE -> {
                    Triple(
                        params["session_key"],
                        "workspace",
                        JsonObject(mapOf("cwd" to (params["cwd"] ?: return null))),
                    )
                }

                else -> {
                    return null
                }
            }
        val sessionId = (sessionKey as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val key = "$sessionId|$operation|$payload"
        retained[key]?.let { return it }
        val revision = revisions[sessionId] ?: return null
        val fenced = operation in setOf("workspace", "compress", "delete")
        if (fenced && generation == null) return null
        val body =
            buildMap<String, JsonElement> {
                put("session_id", JsonPrimitive(sessionId))
                put("request_id", JsonPrimitive(UUID.randomUUID().toString()))
                put("expected_revision", JsonPrimitive(revision))
                if (fenced) put("expected_generation", JsonPrimitive(generation))
                put("operation", JsonPrimitive(operation))
                put("payload", payload)
            }
        return JsonObject(body).also { retained[key] = it }
    }

    /** Remember which request carries [params] so its answer can settle it. */
    fun track(
        rpcId: String,
        params: JsonObject,
    ) {
        inFlight[rpcId] = params
    }

    /** The owner answered request [rpcId]: an applied edit is done; a typed fence refusal wrote nothing. */
    fun settle(
        rpcId: String,
        result: JsonElement?,
        data: JsonElement?,
    ) {
        val params = inFlight.remove(rpcId) ?: return
        val sessionId = (params["session_id"] as? JsonPrimitive)?.contentOrNull ?: return
        if (result is JsonObject) {
            (result["revision"] as? JsonPrimitive)?.intOrNull?.let { adopt(sessionId, it) }
            forget(params)
            return
        }
        val refusal = data as? JsonObject ?: return
        val reason = (refusal["reason"] as? JsonPrimitive)?.contentOrNull
        val operation = (params["operation"] as? JsonPrimitive)?.contentOrNull
        val wroteNothing =
            reason == "revision_conflict" || reason == "stale_generation" ||
                (reason == "session_busy" && operation in setOf("workspace", "delete")) ||
                (reason == "runtime_coordination_required" && operation == "delete")
        if (!wroteNothing) return
        // The refusal names the current revision, so the retry presents it as a fresh request.
        forget(params)
        (refusal["revision"] as? JsonPrimitive)?.intOrNull?.let { adopt(sessionId, it) }
    }

    private fun adopt(
        sessionId: String,
        revision: Int,
    ) {
        revisions.merge(sessionId, revision) { old, new -> maxOf(old, new) }
    }

    private fun forget(params: JsonObject) {
        val id = params["request_id"]
        retained.entries.removeIf { it.value["request_id"] == id }
    }
}
