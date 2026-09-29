package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigModelTypesTest {
    private val json = OkHttpProvider.json

    @Test
    fun configSetEncodesOnlyKeyAndValueByDefault() {
        assertEquals(
            buildJsonObject {
                put("key", "fast")
                put("value", "normal")
            },
            json.encodeToJsonElement(ConfigSetParams.serializer(), ConfigSetParams(key = "fast", value = "normal")),
        )
    }

    @Test
    fun configSetEncodesSessionScopeAndConfirmWhenPresent() {
        assertEquals(
            buildJsonObject {
                put("key", "model")
                put("value", "gpt-4o --provider openai --session")
                put("session_id", "s1")
                put("scope", "global")
                put("confirm_expensive_model", true)
            },
            json.encodeToJsonElement(
                ConfigSetParams.serializer(),
                ConfigSetParams(
                    key = "model",
                    value = "gpt-4o --provider openai --session",
                    sessionId = "s1",
                    scope = "global",
                    confirmExpensiveModel = true,
                ),
            ),
        )
    }

    @Test
    fun configGetOmitsNullSessionAndCwd() {
        assertEquals(
            buildJsonObject {
                put("key", "reasoning")
                put("session_id", "s1")
            },
            json.encodeToJsonElement(
                ConfigGetParams.serializer(),
                ConfigGetParams(key = "reasoning", sessionId = "s1"),
            ),
        )
    }

    @Test
    fun modelOptionsKeepsExplicitFalseAndOmitsUnsetFlags() {
        assertEquals(
            buildJsonObject {
                put("refresh", true)
                put("include_unconfigured", false)
            },
            json.encodeToJsonElement(
                ModelOptionsParams.serializer(),
                ModelOptionsParams(refresh = true, includeUnconfigured = false),
            ),
        )
        assertEquals(
            buildJsonObject { },
            json.encodeToJsonElement(ModelOptionsParams.serializer(), ModelOptionsParams()),
        )
    }

    @Test
    fun clientCapabilitiesAlwaysEncodesServerRequests() {
        assertEquals(
            buildJsonObject { put("server_requests", true) },
            json.encodeToJsonElement(
                ClientCapabilitiesParams.serializer(),
                ClientCapabilitiesParams(serverRequests = true),
            ),
        )
    }

    @Test
    fun descriptorsAreRegisteredWithContractNames() {
        listOf(
            RpcMethods.CONFIG_SET to "config.set",
            RpcMethods.CONFIG_GET to "config.get",
            RpcMethods.MODEL_OPTIONS to "model.options",
            RpcMethods.CLIENT_CAPABILITIES to "client.capabilities",
        ).forEach { (method, name) ->
            assertTrue("$name not in RpcMethods.all", method in RpcMethods.all)
            assertEquals(name, method.name)
        }
    }
}
