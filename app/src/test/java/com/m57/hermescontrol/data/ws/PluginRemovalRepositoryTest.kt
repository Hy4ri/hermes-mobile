package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRemovalRepositoryTest {
    private fun removalRepo(handler: suspend (String, Map<String, Any>) -> Any?) =
        PluginRemovalRepository(fakeCaller(handler))

    @Test
    fun `remove sends scoped gateway action and decodes success`() =
        runTest {
            var method = ""
            var params: Map<String, Any> = emptyMap()
            val repository =
                removalRepo { requestedMethod, requestedParams ->
                    method = requestedMethod
                    params = requestedParams
                    Json.parseToJsonElement("""{"ok":true,"name":"demo"}""")
                }

            val result = repository.remove("demo")

            assertEquals(WsMethods.PLUGINS_MANAGE, method)
            assertTrue(WsMethods.PLUGINS_MANAGE in WsMethods.PROFILE_SCOPED_METHODS)
            assertEquals(mapOf("action" to "remove", "name" to "demo"), params)
            assertTrue(result.ok)
            assertEquals("demo", result.name)
        }

    @Test
    fun `refusal is a typed failure and missing ok cannot masquerade as success`() =
        runTest {
            val refused = removalRepo { _, _ -> mapOf("ok" to false, "error" to "bundled plugin") }
            assertFalse(refused.remove("builtin").ok)
            assertEquals("bundled plugin", refused.remove("builtin").error)

            val malformed = removalRepo { _, _ -> mapOf("name" to "demo") }
            try {
                malformed.remove("demo")
                throw AssertionError("Missing ok must fail decoding")
            } catch (_: SerializationException) {
                // A partial response is not removal confirmation.
            }
        }
}
