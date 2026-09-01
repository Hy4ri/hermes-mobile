package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerToolInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.ceil

/**
 * Client-side parser for MCP server JSON configs (Claude Desktop / Cursor formats, single server JSON).
 * Issue #1029.
 */
object McpJsonParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Parses pasted JSON text into a list of [AddMcpServerRequest] models.
     * Returns an empty list if no valid server entries are found or if parsing fails.
     */
    fun parse(text: String): List<AddMcpServerRequest> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rootElement: JsonElement =
            try {
                json.parseToJsonElement(trimmed)
            } catch (_: Exception) {
                return emptyList()
            }

        if (rootElement !is JsonObject) return emptyList()

        // 1. Check for wrapped "mcpServers" or "mcp_servers" container
        val wrapper = rootElement["mcpServers"] ?: rootElement["mcp_servers"]
        if (wrapper is JsonObject) {
            return parseServerMap(wrapper)
        }

        // 2. Check if the root object itself is a single server definition (has "command" or "url" directly)
        if (isServerObject(rootElement)) {
            val name =
                rootElement["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: inferName(rootElement)
            val req = parseSingleServer(name, rootElement)
            return if (req != null) listOf(req) else emptyList()
        }

        // 3. Check if the root object is a dictionary of name -> serverObject
        val mapEntries = parseServerMap(rootElement)
        if (mapEntries.isNotEmpty()) {
            return mapEntries
        }

        return emptyList()
    }

    private fun parseServerMap(obj: JsonObject): List<AddMcpServerRequest> {
        val results = mutableListOf<AddMcpServerRequest>()
        for ((name, element) in obj) {
            if (element is JsonObject && isServerObject(element)) {
                val req = parseSingleServer(name, element)
                if (req != null) {
                    results.add(req)
                }
            }
        }
        return results
    }

    private fun isServerObject(obj: JsonObject): Boolean =
        obj.containsKey("command") || obj.containsKey("url") || obj.containsKey("transport")

    private fun parseSingleServer(
        name: String,
        obj: JsonObject,
    ): AddMcpServerRequest? {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null

        val url =
            obj["url"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val command =
            obj["command"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        if (url == null && command == null) return null

        val args: List<String>? =
            when (val argsElement = obj["args"]) {
                is JsonArray -> argsElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> null
            }

        val env: Map<String, String>? =
            when (val envElement = obj["env"]) {
                is JsonObject -> {
                    envElement.entries
                        .mapNotNull { (k, v) ->
                            v.jsonPrimitive.contentOrNull?.let { k to it }
                        }.toMap()
                        .takeIf { it.isNotEmpty() }
                }

                else -> {
                    null
                }
            }

        // Auth detection from "auth" or "headers"
        var auth = obj["auth"]?.jsonPrimitive?.contentOrNull?.lowercase()
        var bearerToken = obj["bearerToken"]?.jsonPrimitive?.contentOrNull

        val headers = obj["headers"]
        if (headers is JsonObject) {
            val authHeader =
                headers["Authorization"]?.jsonPrimitive?.contentOrNull
                    ?: headers["authorization"]?.jsonPrimitive?.contentOrNull
            if (authHeader != null) {
                auth = "header"
                if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    bearerToken = authHeader.substring(7).trim()
                } else {
                    bearerToken = authHeader.trim()
                }
            }
        }

        val finalAuth =
            when (auth) {
                "oauth" -> "oauth"
                "header" -> "header"
                else -> "none"
            }

        return AddMcpServerRequest(
            name = cleanName,
            url = if (command == null) url else null,
            command = command,
            args = args,
            env = env,
            auth = finalAuth,
            bearerToken = if (finalAuth == "header") bearerToken else null,
        )
    }

    private fun inferName(obj: JsonObject): String {
        val command = obj["command"]?.jsonPrimitive?.contentOrNull?.trim()
        val args = obj["args"]
        if (!command.isNullOrEmpty()) {
            if (args is JsonArray && args.isNotEmpty()) {
                val firstArg = args[0].jsonPrimitive.contentOrNull
                if (firstArg != null && !firstArg.startsWith("-")) {
                    return sanitizeName(firstArg.split("/").last())
                }
            }
            return sanitizeName(command.split("/").last())
        }
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!url.isNullOrEmpty()) {
            return try {
                val host = java.net.URI(url).host ?: "server"
                host.split(".").firstOrNull { it != "api" && it != "www" && it != "mcp" } ?: "server"
            } catch (_: Exception) {
                "server"
            }
        }
        return "server"
    }

    private fun sanitizeName(raw: String): String =
        raw
            .replace(Regex("""\.(cjs|js|mjs|py|ts)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(mcp-server-|server-|mcp-)"""), "")
            .replace(Regex("""(-mcp-server|-mcp|-server)$"""), "")
            .ifEmpty { "server" }
}

/**
 * Token overhead estimator for MCP server tool schemas (issue #1029).
 */
object McpTokenEstimator {
    /**
     * Approximate token overhead for a server's tool schemas.
     * Sum of ceil(schema_chars / 4.0) over tools carrying valid schema_chars.
     */
    fun estimateTokens(tools: List<McpServerToolInfo>): Int? {
        var total = 0
        var sawValid = false
        for (tool in tools) {
            val chars = tool.schema_chars
            if (chars != null && chars > 0) {
                total += ceil(chars / 4.0).toInt()
                sawValid = true
            }
        }
        return if (sawValid) total else null
    }

    fun formatTokenOverhead(
        toolCount: Int,
        tokenEstimate: Int?,
    ): String {
        if (tokenEstimate == null) {
            return if (toolCount == 1) "1 tool" else "$toolCount tools"
        }
        val tokenFormatted =
            if (tokenEstimate >= 1000) {
                String.format(Locale.US, "%.1fk", tokenEstimate / 1000.0)
            } else {
                tokenEstimate.toString()
            }
        val toolStr = if (toolCount == 1) "1 tool" else "$toolCount tools"
        return "$toolStr • ~$tokenFormatted tokens"
    }
}

/**
 * Health status for visual color-coded badges (issue #1029).
 */
enum class McpHealthStatus {
    HEALTHY,
    AUTH_REQUIRED,
    ERROR,
    TESTING,
    UNKNOWN,
    ;

    companion object {
        fun resolve(
            isTesting: Boolean,
            testResult: McpServerTestResponse?,
            serverStatus: String?,
            serverError: String?,
        ): McpHealthStatus {
            if (isTesting) return TESTING
            if (testResult != null) {
                if (testResult.ok) return HEALTHY
                val err = (testResult.error ?: "").lowercase()
                if (err.contains("oauth") || err.contains("auth") || err.contains("token")) {
                    return AUTH_REQUIRED
                }
                return ERROR
            }
            if (!serverError.isNullOrBlank()) {
                val err = serverError.lowercase()
                if (err.contains("oauth") || err.contains("auth") || err.contains("token")) {
                    return AUTH_REQUIRED
                }
                return ERROR
            }
            if (serverStatus != null) {
                return when (serverStatus.lowercase()) {
                    "running", "ok", "connected", "healthy" -> HEALTHY
                    "error", "failed" -> ERROR
                    else -> UNKNOWN
                }
            }
            return UNKNOWN
        }
    }
}
