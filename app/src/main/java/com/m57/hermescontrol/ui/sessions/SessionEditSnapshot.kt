package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.remote.HermesApiService
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A metadata edit token, not a transcript cache validator. Null means an older backend. */
internal data class SessionEditSnapshot(
    val exists: Boolean,
    val revision: Long,
    val generation: Long?,
) {
    companion object {
        suspend fun read(
            api: HermesApiService,
            sessionId: String,
        ): SessionEditSnapshot? {
            val response = api.getSessionMutationSnapshot(sessionId)
            if (response.code() == 404 || response.code() == 405) return null
            check(response.isSuccessful) { "Could not check session changes" }
            val snapshot = requireNotNull(response.body()) { "Missing session snapshot" }
            check(snapshot.sessionId == sessionId) { "Session snapshot identity changed" }
            val revision = (snapshot.runtimeRevision as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val generation = (snapshot.runtimeGeneration as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            check(revision != null && revision >= 0) { "Invalid session revision" }
            check(generation == null || generation >= 0) { "Invalid session generation" }
            check(snapshot.runtimeGeneration == null || snapshot.runtimeGeneration == JsonNull || generation != null) {
                "Invalid session generation"
            }
            return SessionEditSnapshot(snapshot.exists, revision, generation)
        }
    }
}
