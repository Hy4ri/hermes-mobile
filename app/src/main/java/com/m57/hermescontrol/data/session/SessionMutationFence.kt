package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Runtime revision (and generation) a session write must quote; current backends refuse unfenced writes. */
data class SessionMutationFence(
    val revision: Int,
    val generation: Int?,
)

/** Null when the backend has no snapshot endpoint (older backends take unfenced writes). */
suspend fun fetchSessionMutationFence(
    api: HermesApiService,
    sessionId: String,
): SessionMutationFence? {
    val snapshot = (safeApiCall { api.getSessionMutationSnapshot(sessionId) } as? NetworkResult.Success)?.data
    val revision =
        snapshot
            ?.runtimeRevision
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull() ?: return null
    return SessionMutationFence(
        revision = revision,
        generation =
            snapshot.runtimeGeneration
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull(),
    )
}

/** [body] with a fresh request_id and the session's current revision; unchanged when there is no fence. */
suspend fun fenceSessionPatch(
    api: HermesApiService,
    sessionId: String,
    body: SessionRenameRequest,
): SessionRenameRequest {
    val fence = fetchSessionMutationFence(api, sessionId) ?: return body
    return body.copy(
        requestId = UUID.randomUUID().toString(),
        expectedRevision = fence.revision,
        expectedGeneration = fence.generation,
    )
}
