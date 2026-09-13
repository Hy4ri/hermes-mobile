package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectorConnectItem
import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorConnectStatus
import com.m57.hermescontrol.data.model.ConnectorConnectSummary
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorItem
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.model.ConnectorStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Dedicated parser for connector payloads with strict envelope validation,
 * resilient type coercion, legacy alias normalization, and safe error mapping.
 */
object ConnectorParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Parse raw result from `connectors.list` RPC.
     * Fails with [ConnectorError.MalformedEnvelope] if the required envelope structure is missing.
     */
    fun parseListResult(raw: Any?): ConnectorListResult {
        val root =
            toJsonElement(raw) as? JsonObject
                ?: return ConnectorListResult.Error(
                    ConnectorError.MalformedEnvelope("Response envelope must be a JSON object."),
                )

        // Required envelope check: "connectors" must exist and be an array.
        val connectorsArray =
            root["connectors"] as? JsonArray
                ?: return ConnectorListResult.Error(
                    ConnectorError.MalformedEnvelope("Envelope missing required 'connectors' array."),
                )

        // Optional 'available' flag (defaults to true if omitted, unless explicitly false)
        val available = parseBoolean(root["available"], default = true)

        val items = mutableListOf<ConnectorItem>()
        val seenSlugs = mutableSetOf<String>()
        for (element in connectorsArray) {
            val itemObj = element as? JsonObject ?: continue
            val item = parseConnectorItem(itemObj) ?: continue
            if (seenSlugs.add(item.connector)) {
                items.add(item)
            }
        }

        // Entirely invalid nonempty connectors array must fail rather than looking silently valid empty.
        if (connectorsArray.isNotEmpty() && items.isEmpty()) {
            return ConnectorListResult.Error(
                ConnectorError.MalformedEnvelope("Connectors list contains only invalid items."),
            )
        }

        return ConnectorListResult.Success(
            available = available,
            connectors = items,
        )
    }

    /**
     * Parse raw result from `connectors.connect` RPC.
     * Fails with [ConnectorError.MalformedEnvelope] or [ConnectorError.InvalidResponse] if invalid.
     */
    fun parseConnectResult(raw: Any?): ConnectorConnectResult {
        val rawElement = toJsonElement(raw)

        // Tolerate purposeful legacy bare array form: [ { "connector": "linear", ... } ]
        if (rawElement is JsonArray) {
            val results = mutableListOf<ConnectorConnectItem>()
            val seenSlugs = mutableSetOf<String>()
            for (element in rawElement) {
                val itemObj = element as? JsonObject ?: continue
                val item = parseConnectItem(itemObj) ?: continue
                if (seenSlugs.add(item.connector)) {
                    results.add(item)
                }
            }
            if (results.isNotEmpty()) {
                val summary =
                    ConnectorConnectSummary(
                        total = results.size,
                        active = results.count { it.status == ConnectorConnectStatus.ACTIVE },
                        initiated = results.count { it.status == ConnectorConnectStatus.INITIATED },
                        failed = results.count { it.status == ConnectorConnectStatus.FAILED },
                    )
                return ConnectorConnectResult.Success(
                    results = results,
                    summary = summary,
                )
            }
            return ConnectorConnectResult.Error(
                ConnectorError.InvalidResponse("Connect response contains no valid results."),
            )
        }

        val root =
            rawElement as? JsonObject
                ?: return ConnectorConnectResult.Error(
                    ConnectorError.MalformedEnvelope("Connect response must be a JSON object."),
                )

        // Required envelope check: "results" must exist and be an array.
        val resultsArray =
            root["results"] as? JsonArray
                ?: return ConnectorConnectResult.Error(
                    ConnectorError.MalformedEnvelope("Connect envelope missing required 'results' array."),
                )

        val results = mutableListOf<ConnectorConnectItem>()
        val seenSlugs = mutableSetOf<String>()
        for (element in resultsArray) {
            val itemObj = element as? JsonObject ?: continue
            val item = parseConnectItem(itemObj) ?: continue
            if (seenSlugs.add(item.connector)) {
                results.add(item)
            }
        }

        // Connect must return at least nonempty valid results
        if (results.isEmpty()) {
            return ConnectorConnectResult.Error(
                ConnectorError.InvalidResponse("Connect response contains no valid results."),
            )
        }

        // Current summary object is required in standard envelope
        val summaryObj =
            root["summary"] as? JsonObject
                ?: return ConnectorConnectResult.Error(
                    ConnectorError.MalformedEnvelope("Connect envelope missing required 'summary' object."),
                )
        val summary = parseConnectSummary(summaryObj)

        return ConnectorConnectResult.Success(
            results = results,
            summary = summary,
        )
    }

    private fun parseConnectorItem(obj: JsonObject): ConnectorItem? {
        // Strict string identifier with backend slug validation; legacy aliases supported
        val slug =
            parseIdentifier(obj["connector"])
                ?: parseIdentifier(obj["slug"])
                ?: parseIdentifier(obj["id"])
                ?: return null // Skip items without valid connector identifier

        // Display name: primary "name", legacy aliases "displayName", "title", fallback to slug
        val name =
            parseString(obj["name"])
                ?: parseString(obj["displayName"])
                ?: parseString(obj["title"])
                ?: slug

        val rawConnected = parseBooleanOrNull(obj["connected"])
        val enabled = parseBoolean(obj["enabled"], default = true)

        // Connection status string: primary "connectionStatus", legacy aliases "status", "connection_status"
        val rawStatus =
            parseString(obj["connectionStatus"])
                ?: parseString(obj["status"])
                ?: parseString(obj["connection_status"])

        // Compute typed status respecting connected precedence:
        // When connected boolean is missing (legacy), status "authorized"/"connected"/"active" yields CONNECTED.
        // Explicit connected=false is authoritative and not overridden by status active.
        val status = ConnectorStatus.from(connected = rawConnected, rawStatus = rawStatus)
        val effectiveConnected = rawConnected ?: status.isConnected

        // Connect URL (transient): primary "connect_url", legacy aliases "connectUrl", "auth_url", "authUrl"
        val connectUrl =
            parseString(obj["connect_url"])
                ?: parseString(obj["connectUrl"])
                ?: parseString(obj["auth_url"])
                ?: parseString(obj["authUrl"])

        return ConnectorItem(
            connector = slug,
            name = name,
            connected = effectiveConnected,
            enabled = enabled,
            connectionStatus = rawStatus,
            status = status,
            connectUrl = connectUrl,
        )
    }

    private fun parseConnectItem(obj: JsonObject): ConnectorConnectItem? {
        val slug =
            parseIdentifier(obj["connector"])
                ?: parseIdentifier(obj["slug"])
                ?: parseIdentifier(obj["id"])
                ?: return null

        val rawStatus =
            parseString(obj["status"])
                ?: parseString(obj["connectionStatus"])
                ?: parseString(obj["connection_status"])

        val status = ConnectorConnectStatus.from(rawStatus)

        val connectUrl =
            parseString(obj["connect_url"])
                ?: parseString(obj["connectUrl"])
                ?: parseString(obj["auth_url"])
                ?: parseString(obj["authUrl"])

        return ConnectorConnectItem(
            connector = slug,
            status = status,
            connectUrl = connectUrl,
        )
    }

    private fun parseConnectSummary(obj: JsonObject?): ConnectorConnectSummary {
        if (obj == null) return ConnectorConnectSummary()
        return ConnectorConnectSummary(
            total = parseInt(obj["total"], default = 0),
            active = parseInt(obj["active"], default = 0),
            initiated = parseInt(obj["initiated"], default = 0),
            failed = parseInt(obj["failed"], default = 0),
        )
    }

    /**
     * Map RPC error codes and reasons into typed [ConnectorError]s.
     * Raw server strings and sensitive parameters are never forwarded or exposed in error messages.
     */
    fun mapRpcError(
        code: Int,
        message: String? = null,
        data: JsonElement? = null,
    ): ConnectorError {
        val reason =
            when (data) {
                is JsonObject -> (data["reason"] as? JsonPrimitive)?.content
                is JsonPrimitive -> data.content
                else -> null
            }?.uppercase()?.trim()

        return when {
            code == 4000 || reason == "INVALID_PARAMS" -> {
                ConnectorError.InvalidParams()
            }

            code == 4001 || reason == "NOT_OWNER" -> {
                ConnectorError.NotOwner()
            }

            code == 4031 || reason == "CONNECTORS_UNAVAILABLE" -> {
                ConnectorError.Unavailable()
            }

            code == 5033 || reason == "UNSUPPORTED_RUNTIME" -> {
                ConnectorError.UnsupportedRuntime()
            }

            code == 5034 && reason == "INVALID_CONNECTOR_RESPONSE" -> {
                ConnectorError.InvalidResponse()
            }

            code == 5034 -> {
                ConnectorError.RequestFailed()
            }

            code == -32601 -> {
                ConnectorError.UnsupportedBackend()
            }

            else -> {
                ConnectorError.Other(code = code)
            }
        }
    }

    internal fun parseStrictString(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive && element.isString) {
            return element.content
        }
        return null
    }

    internal fun parseIdentifier(element: JsonElement?): String? {
        val str = parseStrictString(element)?.trim() ?: return null
        return if (ConnectorRepository.isValidSlug(str)) str else null
    }

    internal fun parseString(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) {
            if (!element.isString && element.content.isBlank()) return null
            return element.content
        }
        return null
    }

    internal fun parseBooleanOrNull(element: JsonElement?): Boolean? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) {
            element.booleanOrNull?.let { return it }
            val content = element.content.trim()
            if (content.equals("true", ignoreCase = true) || content == "1") return true
            if (content.equals("false", ignoreCase = true) || content == "0") return false
        }
        return null
    }

    internal fun parseBoolean(
        element: JsonElement?,
        default: Boolean,
    ): Boolean = parseBooleanOrNull(element) ?: default

    internal fun parseInt(
        element: JsonElement?,
        default: Int,
    ): Int {
        if (element == null || element is JsonNull) return default
        if (element is JsonPrimitive) {
            element.intOrNull?.let { return it }
            element.content.toIntOrNull()?.let { return it }
        }
        return default
    }

    fun toJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> {
                JsonNull
            }

            is JsonElement -> {
                value
            }

            is Map<*, *> -> {
                JsonObject(
                    @Suppress("UNCHECKED_CAST")
                    (value as Map<String, Any?>).mapValues { (_, v) -> toJsonElement(v) },
                )
            }

            is List<*> -> {
                JsonArray(value.map { toJsonElement(it) })
            }

            is String -> {
                val trimmed = value.trim()
                if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]"))
                ) {
                    try {
                        json.parseToJsonElement(value)
                    } catch (_: Exception) {
                        JsonPrimitive(value)
                    }
                } else {
                    JsonPrimitive(value)
                }
            }

            is Boolean -> {
                JsonPrimitive(value)
            }

            is Number -> {
                JsonPrimitive(value)
            }

            else -> {
                JsonPrimitive(value.toString())
            }
        }
}
