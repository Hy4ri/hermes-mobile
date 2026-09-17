package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Issue #1164: profile-configured inventory; never pass a live chat session override. */
class ModelOptionsRepository(
    private val connected: () -> Boolean = {
        HermesWsClient.connectionStatus.value == ConnectionStatus.CONNECTED
    },
    private val request: suspend (Map<String, Any>) -> Any? = { params ->
        val deferred = HermesWsClient.request(WsMethods.MODEL_OPTIONS, params)
        try {
            deferred.await()
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
        }
    },
    private val rest: suspend (Boolean) -> NetworkResult<ModelOptionsResponse> = { refresh ->
        safeApiCall { ApiClient.hermesApi.getModelOptions(refresh = refresh, includeUnconfigured = false) }
    },
) {
    suspend fun load(refresh: Boolean = false): NetworkResult<ModelOptionsResponse> {
        currentCoroutineContext().ensureActive()
        if (connected()) {
            try {
                val result = request(mapOf("refresh" to refresh, "include_unconfigured" to false))
                // request() returns raw JsonElement, unlike the pushed-event Map representation.
                val json = result as? JsonElement ?: error("Invalid model.options result")
                return NetworkResult.Success(OkHttpProvider.json.decodeFromJsonElement<ModelOptionsResponse>(json))
            } catch (e: CancellationException) {
                // A disconnected socket may cancel the independent RPC deferred. Only fall back
                // if the screen's own coroutine is still active; never swallow caller cancellation.
                currentCoroutineContext().ensureActive()
            } catch (_: Exception) {
                // Older gateways and malformed payloads keep the existing HTTP behavior.
            }
        }
        currentCoroutineContext().ensureActive()
        return rest(refresh)
    }
}
