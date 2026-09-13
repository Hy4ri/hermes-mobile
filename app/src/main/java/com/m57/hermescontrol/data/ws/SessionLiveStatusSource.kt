package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ActiveSessionsResponse
import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.SessionLiveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Interface for polling and observing live session statuses.
 */
interface SessionLiveStatusSource {
    suspend fun fetchActiveSessionsSnapshot(): LiveSessionSnapshot?
    val events: Flow<WsEvent>
    val connectionStatus: StateFlow<ConnectionStatus>
}

/**
 * Decodes untyped RPC responses from `session.active_list` into a [LiveSessionSnapshot].
 */
object SessionLiveStatusDecoder {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    fun decodeSnapshot(rawResult: Any?): LiveSessionSnapshot? {
        if (rawResult == null) return null
        val response =
            try {
                decodeResponse(rawResult)
            } catch (_: Exception) {
                return null
            } ?: return null

        val statusByStoredId = mutableMapOf<String, SessionLiveStatus>()
        val storedIdByRuntimeId = mutableMapOf<String, String>()

        for (item in response.sessions) {
            val runtimeId = item.id?.trim()
            val storedId = item.sessionKey?.trim()
            if (runtimeId.isNullOrEmpty() || storedId.isNullOrEmpty()) {
                continue
            }
            storedIdByRuntimeId[runtimeId] = storedId
            when (item.status?.trim()?.lowercase()) {
                "working" -> statusByStoredId[storedId] = SessionLiveStatus.WORKING
                "waiting" -> statusByStoredId[storedId] = SessionLiveStatus.WAITING
                else -> {
                    // idle, starting, unknown -> no live indicator
                }
            }
        }

        return LiveSessionSnapshot(
            statusByStoredId = statusByStoredId,
            storedIdByRuntimeId = storedIdByRuntimeId,
        )
    }

    private fun decodeResponse(result: Any): ActiveSessionsResponse? {
        val element: JsonElement =
            when (result) {
                is JsonElement -> result
                is Map<*, *> -> anyToJsonElement(result)
                is List<*> -> JsonArray(result.map { anyToJsonElement(it) })
                else -> {
                    val str = result.toString()
                    try {
                        json.parseToJsonElement(str)
                    } catch (_: Exception) {
                        return null
                    }
                }
            }
        if (element !is JsonObject || !element.containsKey("sessions")) {
            return null
        }
        return json.decodeFromJsonElement(serializer<ActiveSessionsResponse>(), element)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun anyToJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> ->
                JsonObject(
                    (value as Map<String, Any?>).mapValues { (_, v) -> anyToJsonElement(v) },
                )
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}

/**
 * Production implementation of [SessionLiveStatusSource] backed by [HermesWsClient].
 */
class HermesSessionLiveStatusSource(
    private val rpcRequest: suspend (method: String, params: Map<String, Any>) -> Any? = { method, params ->
        HermesWsClient.request(method, params).await()
    },
    eventsProvider: () -> Flow<WsEvent> = { HermesWsClient.events },
    connectionStatusProvider: () -> StateFlow<ConnectionStatus> = { HermesWsClient.connectionStatus },
) : SessionLiveStatusSource {
    override val events: Flow<WsEvent> by lazy { eventsProvider() }
    override val connectionStatus: StateFlow<ConnectionStatus> by lazy { connectionStatusProvider() }

    override suspend fun fetchActiveSessionsSnapshot(): LiveSessionSnapshot? =
        try {
            val raw = rpcRequest(WsMethods.SESSION_ACTIVE_LIST, emptyMap())
            SessionLiveStatusDecoder.decodeSnapshot(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
