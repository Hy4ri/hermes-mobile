package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Manages plugin settings and inspection through the gateway's canonical `plugins.manage` RPC.
 */
class PluginManageRepository(
    private val rpcRequest: suspend (String, Map<String, Any?>) -> Any? = { method, params ->
        val deferred = HermesWsClient.request(method, params.filterValues { it != null }.mapValues { it.value ?: "" })
        try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        }
    },
) {
    suspend fun listPlugins(profile: String? = null): List<AgentPluginRow> {
        val params = mutableMapOf<String, Any?>("action" to "list")
        if (!profile.isNullOrBlank()) {
            params["profile"] = profile
        }
        val result = rpcRequest(WsMethods.PLUGINS_MANAGE, params)
        val element = result.toJsonElement()
        val pluginsElement = (element as? JsonObject)?.get("plugins") ?: return emptyList()
        return OkHttpProvider.json.decodeFromJsonElement<List<AgentPluginRow>>(pluginsElement)
    }

    suspend fun saveSettings(
        key: String,
        values: Map<String, JsonElement>,
        profile: String? = null,
    ): PluginsManageSettingsResult {
        val params =
            mutableMapOf<String, Any?>(
                "action" to "settings",
                "key" to key,
                "values" to JsonObject(values),
            )
        if (!profile.isNullOrBlank()) {
            params["profile"] = profile
        }
        val result = rpcRequest(WsMethods.PLUGINS_MANAGE, params)
        return OkHttpProvider.json.decodeFromJsonElement<PluginsManageSettingsResult>(result.toJsonElement())
    }
}
