package com.m57.hermescontrol.data.ws

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Vendored snapshot of the backend's published gateway contract
 * (`apps/shared/src/gateway-contract.openrpc.json` in hermes-agent), test-only.
 * Refresh with `scripts/sync-gateway-contract.sh`; provenance is in `SOURCE`.
 */
object GatewayContract {
    private val root: JsonObject by lazy {
        val stream =
            requireNotNull(GatewayContract::class.java.getResourceAsStream("/gateway-contract/openrpc.json")) {
                "gateway-contract/openrpc.json missing from test resources"
            }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val schemas: JsonObject get() = root["components"]!!.jsonObject["schemas"]!!.jsonObject

    private fun names(key: String): Set<String> =
        (root[key] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    val methods: Set<String> by lazy { names("methods") }

    /** Server→client request methods (`x-server-requests`). */
    val serverRequests: Set<String> by lazy { names("x-server-requests") }

    private fun paramsSchema(method: String): JsonObject {
        val entry =
            (root["methods"] as JsonArray)
                .map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content == method }
        val ref =
            entry["params"]!!
                .jsonArray
                .first()
                .jsonObject["schema"]!!
                .jsonObject["\$ref"]!!
                .jsonPrimitive.content
        return schemas[ref.substringAfterLast('/')]!!.jsonObject
    }

    /**
     * Keys the backend would reject for [method]: unknown keys when the params
     * schema forbids extras, plus required keys that are absent. Empty means OK.
     * Keys only; value types are not checked.
     */
    fun paramKeyProblems(
        method: String,
        keys: Set<String>,
    ): List<String> {
        if (method !in methods) return listOf("$method is not a contract method")
        val schema = paramsSchema(method)
        val properties = schema["properties"]?.jsonObject?.keys.orEmpty()
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val strict = schema["additionalProperties"]?.jsonPrimitive?.boolean == false
        val problems = mutableListOf<String>()
        if (strict) {
            (keys - properties).sorted().forEach {
                problems += "$method: unknown param '$it' (backend forbids extra keys; allowed: ${properties.sorted()})"
            }
        }
        required.filter { it !in keys }.forEach { problems += "$method: missing required param '$it'" }
        return problems
    }

    /**
     * Verifies that a Kotlinx [descriptor] for [method]'s params matches the published contract.
     * Empty list means OK.
     */
    fun paramsDescriptorProblems(
        method: String,
        descriptor: SerialDescriptor,
    ): List<String> {
        if (method !in methods) return listOf("$method is not a contract method")
        val schema = paramsSchema(method)
        val propertiesObj = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val properties = propertiesObj.keys
        val required =
            schema["required"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()
        val problems = mutableListOf<String>()

        val elementNames = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (name !in properties) {
                problems +=
                    "$method: unknown param '$name' (backend forbids extra keys; allowed: ${properties.sorted()})"
            }
        }

        val missingRequired =
            required.filter { reqName ->
                reqName !in elementNames && reqName != "profile"
            }
        for (missing in missingRequired) {
            problems += "$method: missing required param '$missing'"
        }

        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (name in required) {
                if (descriptor.isElementOptional(i)) {
                    problems += "$method: required param '$name' must not be optional"
                }
                val propSchema = propertiesObj[name]?.jsonObject
                val allowsNull = propSchema?.let { propertyAllowsNull(it) } ?: false
                if (descriptor.getElementDescriptor(i).isNullable && !allowsNull) {
                    problems += "$method: required param '$name' must not be nullable unless schema allows null"
                }
            }
        }

        return problems
    }

    private fun propertyAllowsNull(propertySchema: JsonObject): Boolean {
        val typeElement = propertySchema["type"]
        if (typeElement != null) {
            if (typeElement is JsonArray && typeElement.any { it.jsonPrimitive.content == "null" }) {
                return true
            }
            if (typeElement is JsonPrimitive && typeElement.content == "null") {
                return true
            }
        }
        val anyOf = propertySchema["anyOf"]?.jsonArray
        if (anyOf != null && anyOf.any { it.jsonObject["type"]?.jsonPrimitive?.content == "null" }) {
            return true
        }
        val oneOf = propertySchema["oneOf"]?.jsonArray
        if (oneOf != null && oneOf.any { it.jsonObject["type"]?.jsonPrimitive?.content == "null" }) {
            return true
        }
        return false
    }
}

/**
 * WsMethods constants the backend contract does not list. Every entry needs a
 * reason; the test fails if an entry appears in the contract or is no longer a
 * WsMethods constant, so stale exceptions get removed.
 */
object GatewayContractAllowlist {
    private const val LEGACY = "legacy fallback for gateways before srq request frames (#1119)"

    val entries: Map<String, String> =
        mapOf(
            "gateway.ping" to "handled inline by the WS transport itself (tui_gateway/ws.py), not a contract method",
            "clarify.respond" to LEGACY,
            "sudo.respond" to LEGACY,
            "secret.respond" to LEGACY,
            "vault.unlock.respond" to LEGACY,
            "vault.code.respond" to LEGACY,
            "vault.save_login.respond" to LEGACY,
        )
}
