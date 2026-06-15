package com.m57.hermescontrol.data.ws

/**
 * Outgoing JSON-RPC 2.0 request sent over the WebSocket.
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: Map<String, Any> = emptyMap(),
)

/**
 * Incoming JSON-RPC 2.0 response **or** server-pushed notification.
 *
 * - RPC result/error → [id] is non-null, [result] or [error] is populated.
 * - Event notification → [id] is null, [method] is `"event"`, event data in [params].
 */
data class JsonRpcResponse(
    val jsonrpc: String?,
    val id: String?,
    val result: Any?,
    val error: JsonRpcError?,
    val method: String?,
    val params: Map<String, Any?>?,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)
