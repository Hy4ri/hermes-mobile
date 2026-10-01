package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller

/**
 * Test double for [TypedRpcCaller]: encodes the typed params exactly as the wire would, hands the
 * resulting map to [handler] together with the method name, and returns its value as a JsonElement.
 */
internal fun fakeCaller(handler: suspend (String, Map<String, Any>) -> Any?): TypedRpcCaller =
    object : TypedRpcCaller {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <P, R> call(
            method: RpcMethod<P, R>,
            params: P,
        ): R {
            val encoded = OkHttpProvider.json.encodeToJsonElement(method.params, params)
            val map = (encoded.toAny() as? Map<String, Any>).orEmpty()
            return ConnectorParser.toJsonElement(handler(method.name, map)) as R
        }
    }
