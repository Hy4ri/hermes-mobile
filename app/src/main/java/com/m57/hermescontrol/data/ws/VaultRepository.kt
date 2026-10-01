package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.VaultItem
import com.m57.hermescontrol.data.model.VaultListResponse
import com.m57.hermescontrol.data.model.VaultLockResponse
import com.m57.hermescontrol.data.model.VaultSource
import com.m57.hermescontrol.data.model.VaultSourceSetResponse
import com.m57.hermescontrol.data.model.VaultSourcesResponse
import com.m57.hermescontrol.data.model.VaultUnlockResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.EmptyParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.VaultLockParams
import com.m57.hermescontrol.data.ws.contract.VaultSourceSetParams
import com.m57.hermescontrol.data.ws.contract.VaultUnlockParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Repository for Credential Vault RPC operations over WebSocket (issue #1090).
 */
object VaultRepository {
    private val json get() = OkHttpProvider.json

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> decode(result: Any?): T? {
        if (result == null) return null
        val element: JsonElement =
            when (result) {
                is JsonElement -> result
                is Map<*, *> -> anyToJsonElement(result)
                is List<*> -> JsonArray(result.map { anyToJsonElement(it) })
                else -> JsonPrimitive(result.toString())
            }
        return json.decodeFromJsonElement(serializer<T>(), element)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject((value as Map<String, Any?>).mapValues { (_, v) -> anyToJsonElement(v) })
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    suspend fun getSources(): List<VaultSource> {
        val result = HermesWsClient.call(RpcMethods.VAULT_SOURCES, EmptyParams)
        return decode<VaultSourcesResponse>(result)?.sources ?: emptyList()
    }

    suspend fun setSourceEnabled(
        name: String,
        enabled: Boolean,
    ): Boolean {
        val result =
            HermesWsClient.call(
                RpcMethods.VAULT_SOURCE_SET,
                VaultSourceSetParams(name = name, enabled = enabled),
            )
        return decode<VaultSourceSetResponse>(result)?.enabled == enabled
    }

    suspend fun unlockSource(
        name: String,
        password: String,
    ): Boolean {
        val result =
            HermesWsClient.call(
                RpcMethods.VAULT_UNLOCK,
                VaultUnlockParams(name = name, password = password),
            )
        return decode<VaultUnlockResponse>(result)?.unlocked == true
    }

    suspend fun lockSource(name: String? = null): Boolean {
        val params = VaultLockParams(name = name)
        val result = HermesWsClient.call(RpcMethods.VAULT_LOCK, params)
        return decode<VaultLockResponse>(result)?.locked == true
    }

    suspend fun listItems(): List<VaultItem> {
        val result = HermesWsClient.call(RpcMethods.VAULT_LIST, EmptyParams)
        return decode<VaultListResponse>(result)?.items ?: emptyList()
    }
}
