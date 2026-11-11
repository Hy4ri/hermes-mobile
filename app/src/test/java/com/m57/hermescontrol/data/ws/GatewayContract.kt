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

    /** `methods` plus the canonical session methods the backend lists under `x-canonical-methods`. */
    private val methodEntries: List<JsonObject> by lazy {
        listOf("methods", "x-canonical-methods").flatMap { key ->
            (root[key] as? JsonArray).orEmpty().map { it.jsonObject }
        }
    }

    val methods: Set<String> by lazy { methodEntries.map { it["name"]!!.jsonPrimitive.content }.toSet() }

    /** Server→client request methods (`x-server-requests`). */
    val serverRequests: Set<String> by lazy { names("x-server-requests") }

    /** Methods only the session authority serves (`x-canonical-methods`). */
    val canonicalMethods: Set<String> by lazy { names("x-canonical-methods") }

    /** Methods listed in the legacy `methods` contract; not all are served by the authority. */
    val sharedMethods: Set<String> by lazy { names("methods") }

    private fun paramsSchemas(method: String): List<JsonObject> {
        val entries = methodEntries.filter { it["name"]!!.jsonPrimitive.content == method }
        return schemasOf(entries)
    }

    private fun routeEntries(
        method: String,
        authority: Boolean,
    ): List<JsonObject> {
        val canonical = (root["x-canonical-methods"] as? JsonArray).orEmpty().map { it.jsonObject }
        val shared = (root["methods"] as? JsonArray).orEmpty().map { it.jsonObject }
        // The authority serves its canonical form of a method when it has one; a legacy route has no canonical methods.
        val pool =
            if (authority) {
                canonical
                    .filter {
                        it["name"]!!.jsonPrimitive.content == method
                    }.ifEmpty { shared }
            } else {
                shared
            }
        return pool.filter { it["name"]!!.jsonPrimitive.content == method }
    }

    /** [paramKeyProblems] for the one schema [method] has on a given route. */
    fun routeParamProblems(
        method: String,
        keys: Set<String>,
        authority: Boolean,
    ): List<String> {
        // The shared OpenRPC list includes legacy methods the authority does not register.
        if (authority && method == WsMethods.SESSION_STATUS) {
            return listOf("$method is not served on the authority route")
        }
        val entries = routeEntries(method, authority)
        if (entries.isEmpty()) {
            return listOf("$method is not served on the ${if (authority) "authority" else "legacy"} route")
        }
        val schema = schemasOf(entries).first()
        val properties = schema["properties"]?.jsonObject?.keys.orEmpty()
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val strict = schema["additionalProperties"]?.jsonPrimitive?.boolean == false
        val route = if (authority) "authority" else "legacy"
        val problems = mutableListOf<String>()
        if (strict) (keys - properties).sorted().forEach { problems += "$method ($route): unknown param '$it'" }
        required.filter { it !in keys }.forEach { problems += "$method ($route): missing required param '$it'" }
        return problems
    }

    private fun schemasOf(entries: List<JsonObject>): List<JsonObject> =
        entries.map { entry ->
            val ref =
                entry["params"]!!
                    .jsonArray
                    .first()
                    .jsonObject["schema"]!!
                    .jsonObject["\$ref"]!!
                    .jsonPrimitive.content
            schemas[ref.substringAfterLast('/')]!!.jsonObject
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
        val candidateSchemas = paramsSchemas(method)
        var firstProblems: List<String>? = null
        for (schema in candidateSchemas) {
            val properties = schema["properties"]?.jsonObject?.keys.orEmpty()
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val strict = schema["additionalProperties"]?.jsonPrimitive?.boolean == false
            val problems = mutableListOf<String>()
            if (strict) {
                (keys - properties).sorted().forEach {
                    problems +=
                        "$method: unknown param '$it' (backend forbids extra keys; allowed: ${properties.sorted()})"
                }
            }
            required.filter { it !in keys }.forEach { problems += "$method: missing required param '$it'" }
            if (problems.isEmpty()) return emptyList()
            if (firstProblems == null) firstProblems = problems
        }
        return firstProblems ?: emptyList()
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
        val candidateSchemas = paramsSchemas(method)
        var firstProblems: List<String>? = null
        for (schema in candidateSchemas) {
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

            // No exemptions: decorate() skips injection when no profile is active, so a required `profile`
            // must be declared like any other required param.
            val missingRequired = required.filter { it !in elementNames }
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

            if (problems.isEmpty()) return emptyList()
            if (firstProblems == null) firstProblems = problems
        }

        return firstProblems ?: emptyList()
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
            "sudo.respond" to LEGACY,
            "secret.respond" to LEGACY,
            "vault.unlock.respond" to LEGACY,
            "vault.code.respond" to LEGACY,
            "vault.save_login.respond" to LEGACY,
        )
}
