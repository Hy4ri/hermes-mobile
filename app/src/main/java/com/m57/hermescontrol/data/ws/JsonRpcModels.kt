package com.m57.hermescontrol.data.ws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Outgoing JSON-RPC 2.0 request sent over the WebSocket.
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: Map<String, JsonElement> = emptyMap(),
)

/**
 * Incoming JSON-RPC 2.0 response **or** server-pushed notification.
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val id: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val method: String? = null,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(this.map { it.key.toString() to it.value.toJsonElement() }.toMap())
        is List<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(this.toString())
    }

fun JsonElement.toAny(): Any? =
    when (this) {
        is JsonNull -> {
            null
        }

        is JsonPrimitive -> {
            if (isString) {
                content
            } else {
                booleanOrNull ?: doubleOrNull ?: longOrNull ?: content
            }
        }

        is JsonObject -> {
            mapValues { it.value.toAny() }
        }

        is JsonArray -> {
            map { it.toAny() }
        }
    }
