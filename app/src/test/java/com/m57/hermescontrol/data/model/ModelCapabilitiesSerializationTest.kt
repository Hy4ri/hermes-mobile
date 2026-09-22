package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCapabilitiesSerializationTest {
    private val json = OkHttpProvider.json

    @Test
    fun fullCapabilities_decodeAllNullableFields() {
        val value =
            json.decodeFromString<ModelInfoResponse>(
                """
                {"model":"gpt-5","provider":"openai","capabilities":{
                  "supports_tools":false,"supports_vision":true,"supports_reasoning":true,
                  "context_window":128000,"max_output_tokens":4096,"model_family":"gpt-5"
                }}
                """.trimIndent(),
            )

        assertEquals(false, value.capabilities?.supports_tools)
        assertEquals(true, value.capabilities?.supports_vision)
        assertEquals(true, value.capabilities?.reasoningSupport)
        assertEquals(128000L, value.capabilities?.context_window)
        assertEquals(4096L, value.capabilities?.max_output_tokens)
        assertEquals("gpt-5", value.capabilities?.model_family)
    }

    @Test
    fun partialMissingAndNullCapabilities_preserveUnknown() {
        val partial = json.decodeFromString<ModelInfoResponse>("""{"capabilities":{"supports_vision":false}}""")
        val missing = json.decodeFromString<ModelInfoResponse>("{}")
        val explicitNull = json.decodeFromString<ModelInfoResponse>("""{"capabilities":null}""")

        assertEquals(false, partial.capabilities?.supports_vision)
        assertNull(partial.capabilities?.supports_tools)
        assertNull(partial.capabilities?.reasoningSupport)
        assertNull(missing.capabilities)
        assertNull(explicitNull.capabilities)
    }

    @Test
    fun canonicalReasoning_takesPrecedenceOverLegacyField() {
        val value =
            json.decodeFromString<ModelOptionsResponse>(
                """
                {"providers":[{"slug":"test","name":"Test","capabilities":{
                  "model":{"reasoning":true,"supports_reasoning":false}
                }}]}
                """.trimIndent(),
            )

        assertEquals(
            false,
            value.providers
                .single()
                .capabilities
                ?.get("model")
                ?.reasoningSupport,
        )
    }

    @Test
    fun modelInfoCapabilities_enrichOnlyMatchingCatalogModel() {
        val catalog =
            listOf(
                ModelProvider(
                    slug = "openai",
                    name = "OpenAI",
                    models = listOf("gpt-5", "gpt-4o"),
                    capabilities =
                        mapOf(
                            "gpt-5" to ModelCapabilities(fast = true, reasoning = true),
                            "gpt-4o" to ModelCapabilities(fast = false, reasoning = false),
                        ),
                ),
            )
        val info =
            ModelInfoResponse(
                model = "gpt-5",
                provider = "openai",
                capabilities =
                    ModelCapabilities(
                        supports_tools = false,
                        supports_vision = true,
                        supports_reasoning = true,
                        context_window = 128000,
                        max_output_tokens = 4096,
                        model_family = "gpt-5",
                    ),
            )

        val enriched = catalog.withModelInfoCapabilities(info)

        val active = enriched.single().capabilities?.get("gpt-5")
        assertEquals(true, active?.fast)
        assertEquals(false, active?.supports_tools)
        assertEquals(true, active?.supports_vision)
        assertEquals(128000L, active?.context_window)
        assertEquals("gpt-5", active?.model_family)
        assertEquals(
            ModelCapabilities(fast = false, reasoning = false),
            enriched.single().capabilities?.get("gpt-4o"),
        )
    }

    @Test
    fun modelInfoCapabilities_doNotBleedIntoDifferentProviderOrModel() {
        val catalog =
            listOf(
                ModelProvider(
                    slug = "openai",
                    name = "OpenAI",
                    models = listOf("gpt-5"),
                    capabilities = mapOf("gpt-5" to ModelCapabilities(reasoning = true)),
                ),
            )
        val otherProfileInfo =
            ModelInfoResponse(
                model = "claude-sonnet-4-5",
                provider = "anthropic",
                capabilities = ModelCapabilities(supports_vision = true),
            )

        assertEquals(catalog, catalog.withModelInfoCapabilities(otherProfileInfo))
    }
}
