package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.HermesRpcCaller
import com.m57.hermescontrol.data.ws.contract.PluginsManageParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Manages plugin settings and inspection through the gateway's canonical `plugins.manage` RPC.
 */
class PluginManageRepository(
    private val caller: TypedRpcCaller = HermesRpcCaller,
) {
    suspend fun listPlugins(profile: String? = null): List<AgentPluginRow> {
        val element =
            caller.call(
                RpcMethods.PLUGINS_MANAGE,
                PluginsManageParams(action = "list", profile = profile.orNullIfBlank()),
            )
        val pluginsElement = (element as? JsonObject)?.get("plugins") ?: return emptyList()
        return OkHttpProvider.json.decodeFromJsonElement<List<AgentPluginRow>>(pluginsElement)
    }

    suspend fun saveSettings(
        key: String,
        values: Map<String, JsonElement>,
        profile: String? = null,
    ): PluginsManageSettingsResult {
        val params =
            PluginsManageParams(
                action = "settings",
                key = key,
                values = JsonObject(values),
                profile = profile.orNullIfBlank(),
            )
        val result = caller.call(RpcMethods.PLUGINS_MANAGE, params)
        return OkHttpProvider.json.decodeFromJsonElement<PluginsManageSettingsResult>(result)
    }

    /** Unattended update of every plugin the backend's own update plan would move. */
    suspend fun updateAll(profile: String? = null): PluginUpdateAllResult {
        val result =
            caller.call(
                RpcMethods.PLUGINS_MANAGE,
                PluginsManageParams(action = "update_all", profile = profile.orNullIfBlank()),
            )
        return OkHttpProvider.json.decodeFromJsonElement<PluginUpdateAllResult>(result)
    }

    /** Read the profile's `plugins.auto_apply` switch and check cadence. */
    suspend fun autoUpdateState(profile: String? = null): PluginAutoUpdateState {
        val result =
            caller.call(
                RpcMethods.PLUGINS_MANAGE,
                PluginsManageParams(action = "auto_update", profile = profile.orNullIfBlank()),
            )
        return OkHttpProvider.json.decodeFromJsonElement<PluginAutoUpdateState>(result)
    }

    /** Write `plugins.auto_apply` for the profile and return the state the backend reports. */
    suspend fun setAutoUpdate(
        enabled: Boolean,
        profile: String? = null,
    ): PluginAutoUpdateState {
        val result =
            caller.call(
                RpcMethods.PLUGINS_MANAGE,
                PluginsManageParams(action = "auto_update", enable = enabled, profile = profile.orNullIfBlank()),
            )
        return OkHttpProvider.json.decodeFromJsonElement<PluginAutoUpdateState>(result)
    }

    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
