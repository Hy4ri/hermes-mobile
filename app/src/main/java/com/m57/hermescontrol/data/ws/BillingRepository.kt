package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.SubscriptionChangeRequest
import com.m57.hermescontrol.data.model.SubscriptionChangeResponse
import com.m57.hermescontrol.data.model.SubscriptionPreviewResponse
import com.m57.hermescontrol.data.model.SubscriptionResumeResponse
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.SubscriptionUpgradeResponse
import com.m57.hermescontrol.data.model.UsageBarsResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
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
 */
object BillingRepository {
    private val json get() = OkHttpProvider.json

    private inline fun <reified T> decode(result: Any?): T? {
        val element = result as? JsonElement ?: return null
        return json.decodeFromJsonElement(serializer<T>(), element)
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
