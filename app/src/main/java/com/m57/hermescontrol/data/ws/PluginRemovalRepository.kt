package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class PluginRemovalResult(
    val ok: Boolean,
    val name: String? = null,
    val error: String? = null,
)

/** Removes a user-installed plugin through the gateway's canonical plugin manager. */
class PluginRemovalRepository(
    private val rpcRequest: suspend (String, Map<String, Any>) -> Any? = { method, params ->
        val deferred = HermesWsClient.request(method, params)
        try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        }
    },
) {
    suspend fun remove(name: String): PluginRemovalResult {
        val result = rpcRequest(WsMethods.PLUGINS_MANAGE, mapOf("action" to "remove", "name" to name))
        return Json.decodeFromJsonElement<PluginRemovalResult>(result.toJsonElement())
    }
}
