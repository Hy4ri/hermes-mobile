package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.SubscriptionChangeRequest
import com.m57.hermescontrol.data.model.SubscriptionChangeResponse
import com.m57.hermescontrol.data.model.SubscriptionPreviewResponse
import com.m57.hermescontrol.data.model.SubscriptionResumeResponse
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.SubscriptionUpgradeResponse
import com.m57.hermescontrol.data.model.UsageBarsResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Client for the billing/subscription WebSocket RPC surface (issue #628).
 *
 * Mirrors [HermesWsClient.request]: every call returns the backend `result`
 * payload decoded into a typed model, or throws [HermesWsClient.HermesRpcException]
 * on an RPC error (e.g. `-32601 unknown method`).
 *
 * Defensive note: an old cached mobile build may still dispatch the removed
 * `credits.view` method and receive `-32601`. We never dispatch it ourselves,
 * but [HermesRpcException] from any billing call is surfaced as a clean
 * "feature unavailable" state by the ViewModel rather than a crash.
 *
 * NOTE: [HermesWsClient] hands back `result` already converted via
 * `JsonElement.toAny()` (a `Map<String, Any?>`), not a raw `JsonElement`.
 * [decode] therefore normalizes whatever arrives back to JSON before
 * deserializing, so both shapes decode correctly.
 */
object BillingRepository {
    private val json get() = OkHttpProvider.json

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> decode(result: Any?): T? {
        if (result == null) return null
        // `result` may arrive as a kotlinx JsonElement OR as a Map produced by
        // JsonElement.toAny() in HermesWsClient. Normalize both back to a JSON
        // string (via a recursive Any->JsonElement walk) before deserializing.
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

    suspend fun getSubscriptionState(): SubscriptionStateResponse? {
        val result = HermesWsClient.request(WsMethods.SUBSCRIPTION_STATE).await()
        return decode(result)
    }

    suspend fun getUsageBars(): UsageBarsResponse? {
        val result = HermesWsClient.request(WsMethods.USAGE_BARS).await()
        return decode(result)
    }

    suspend fun previewSubscription(subscriptionTypeId: String): SubscriptionPreviewResponse? {
        val result =
            HermesWsClient
                .request(
                    WsMethods.SUBSCRIPTION_PREVIEW,
                    mapOf("subscription_type_id" to subscriptionTypeId),
                ).await()
        return decode(result)
    }

    suspend fun changeSubscription(request: SubscriptionChangeRequest): SubscriptionChangeResponse? {
        val result =
            HermesWsClient
                .request(
                    WsMethods.SUBSCRIPTION_CHANGE,
                    buildMap {
                        request.subscription_type_id?.let { put("subscription_type_id", it) }
                        request.cancel?.let { put("cancel", it) }
                    },
                ).await()
        return decode(result)
    }

    suspend fun resumeSubscription(): SubscriptionResumeResponse? {
        val result = HermesWsClient.request(WsMethods.SUBSCRIPTION_RESUME).await()
        return decode(result)
    }

    suspend fun upgradeSubscription(subscriptionTypeId: String): SubscriptionUpgradeResponse? {
        val result =
            HermesWsClient
                .request(
                    WsMethods.SUBSCRIPTION_UPGRADE,
                    mapOf("subscription_type_id" to subscriptionTypeId),
                ).await()
        return decode(result)
    }
}
