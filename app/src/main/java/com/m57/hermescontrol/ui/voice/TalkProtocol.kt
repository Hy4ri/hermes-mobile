package com.m57.hermescontrol.ui.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.rows(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Validation is deliberately exact: labels, recent sessions and defaults are not authority. */
internal object TalkProtocol {
    const val MODEL = "gpt-realtime-2.1"

    fun validateStatus(status: JsonObject) {
        check(status.flag("ok") && status.flag("configured")) { "Hermes Talk subscription is unavailable" }
        check(status.text("voiceMode") == "native" && status.text("model") == MODEL) {
            "Hermes Talk must use native gpt-realtime-2.1; other models and cascade are not supported"
        }
        check(status.text("source") == "codex-oauth") { "Codex subscription required; API billing is disabled" }
        check(status.obj("mobileRealtime")["protocol"] == JsonPrimitive(1)) { "Compatible Talk mobile patch required" }
        check(
            status.obj("taskContinuity").flag("supported"),
        ) { "Hermes host does not support canonical task continuity" }
    }

    fun exactTarget(
        catalog: JsonObject,
        profile: String,
        sessionId: String,
    ): String {
        require(profile.isNotBlank() && sessionId.isNotBlank())
        check(catalog.flag("ok")) { "Target catalog is unavailable" }
        val matches =
            catalog.rows("targets").filter {
                it.text("peer_id") == "local" && it.text("profile") == profile && it.text("session_id") == sessionId
            }
        check(matches.size == 1) { "Current chat has no unique authorized Hermes Talk target" }
        return matches.single().text("target_id").also { check(it.isNotBlank()) { "Missing authorized target ID" } }
    }

    fun validateSession(
        session: JsonObject,
        targetId: String,
        tabId: String,
        profile: String,
        sessionId: String,
    ) {
        check(
            session.flag("ok") && session.text("voiceMode") == "native" && session.text("model") == MODEL &&
                session.text("authSource") == "codex-oauth" &&
                session.obj("mobileRealtime")["protocol"] == JsonPrimitive(1),
        ) { "Voice session model or billing mismatch" }
        val task = session.obj("task")
        check(
            task.text("target_id") == targetId && task.text("tab_id") == tabId &&
                task.text("profile") == profile && task.text("session_id") == sessionId &&
                task.text("peer_id") == "local" && task.text("connection_id").isNotBlank() &&
                ((task["generation"] as? JsonPrimitive)?.intOrNull ?: -1) >= 0,
        ) { "Voice owner binding mismatch" }
        check(session.text("clientSecret").isNotBlank()) { "Missing ephemeral voice credential" }
    }
}
