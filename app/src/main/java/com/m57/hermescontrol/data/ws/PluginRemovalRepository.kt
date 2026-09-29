package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.HermesRpcCaller
import com.m57.hermescontrol.data.ws.contract.PluginsManageParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
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
    private val caller: TypedRpcCaller = HermesRpcCaller,
) {
    suspend fun remove(name: String): PluginRemovalResult {
        val result = caller.call(RpcMethods.PLUGINS_MANAGE, PluginsManageParams(action = "remove", name = name))
        return Json.decodeFromJsonElement<PluginRemovalResult>(result)
    }
}
