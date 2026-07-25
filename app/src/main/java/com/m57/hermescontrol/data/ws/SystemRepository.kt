package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.model.BatteryStatus
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Client for the system WebSocket RPC surface (issue #711).
 *
 * Mirrors [BillingRepository]: every call returns the backend `result` payload
 * decoded into a typed model, or throws [HermesWsClient.HermesRpcException] on
 * an RPC error. [HermesWsClient] hands `result` back already converted via
 * `JsonElement.toAny()` (a `Map<String, Any?>`), so [decode] normalizes both
 * shapes before deserializing.
 */
object SystemRepository {
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

    /**
     * Fetch the backend host battery status via `system.battery`.
     *
     * Fails OPEN to `available: false` on any RPC error / timeout — matching the
     * backend's own semantics, so a headless host (desktop/server/VM) is treated
     * identically to "no battery" and the UI renders nothing.
     */
    suspend fun getBatteryStatus(): BatteryStatus =
        try {
            if (!HermesWsClient.isConnected) return BatteryStatus(available = false)
            val result = HermesWsClient.request(WsMethods.SYSTEM_BATTERY).await()
            decode<BatteryStatus>(result) ?: BatteryStatus(available = false)
        } catch (e: Exception) {
            // Fail OPEN to "no battery" on any RPC error, decode failure, or
            // timeout — never let a bad/headless payload crash the System screen.
            try {
                if (BuildConfig.DEBUG) Log.w(TAG, "system.battery failed: ${e.message}")
            } catch (_: Throwable) {
                // Ignore log failures in unmocked JVM unit tests
            }
            BatteryStatus(available = false)
        }

    private const val TAG = "SystemRepository"
}
