package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.contract.ModelOptionsParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
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
    private val request: suspend (ModelOptionsParams) -> JsonElement = { params ->
        HermesWsClient.call(RpcMethods.MODEL_OPTIONS, params)
    },
    private val rest: suspend (Boolean) -> NetworkResult<ModelOptionsResponse> = { refresh ->
        safeApiCall { ApiClient.hermesApi.getModelOptions(refresh = refresh, includeUnconfigured = false) }
    },
) {
    suspend fun load(refresh: Boolean = false): NetworkResult<ModelOptionsResponse> {
        currentCoroutineContext().ensureActive()
        if (connected()) {
            try {
                val json = request(ModelOptionsParams(refresh = refresh, includeUnconfigured = false))
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
