package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsRepositoryTest {
    private val fallback = NetworkResult.Success(ModelOptionsResponse(emptyList()))

    @Test
    fun `connected uses raw JSON and preserves refresh flags without REST`() =
        runTest {
            val repository =
                ModelOptionsRepository(
                    connected = { true },
                    request = { params ->
                        assertEquals(mapOf("refresh" to true, "include_unconfigured" to false), params)
                        Json.parseToJsonElement("""{"providers":[{"slug":"test","name":"Test","models":["a"]}]}""")
                    },
                    rest = { error("REST must not run") },
                )
            val result = repository.load(true) as NetworkResult.Success
            assertEquals(
                "test",
                result.data.providers
                    .single()
                    .slug,
            )
            assertTrue(WsMethods.MODEL_OPTIONS in WsMethods.PROFILE_SCOPED_METHODS)
        }

    @Test
    fun `disconnected bypasses RPC`() =
        runTest {
            val repository =
                ModelOptionsRepository(
                    connected = { false },
                    request = { error("RPC must not run") },
                    rest = { refresh ->
                        assertTrue(refresh)
                        fallback
                    },
                )
            assertEquals(fallback, repository.load(true))
        }

    @Test
    fun `RPC failure and malformed envelopes use REST`() =
        runTest {
            for (wire in listOf(null, "{}", "[]", """{"providers":null}""")) {
                var restCalls = 0
                val repository =
                    ModelOptionsRepository(
                        connected = { true },
                        request = { if (wire == null) error("Unsupported method") else Json.parseToJsonElement(wire) },
                        rest = {
                            restCalls++
                            fallback
                        },
                    )
                assertEquals(fallback, repository.load())
                assertEquals(1, restCalls)
            }
        }

    @Test
    fun `cancelled screen never falls back`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var restCalls = 0
            val repository =
                ModelOptionsRepository(
                    connected = { true },
                    request = {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                    rest = {
                        restCalls++
                        fallback
                    },
                )
            val job = async { repository.load() }
            started.await()
            job.cancel()
            job.join()
            assertEquals(0, restCalls)
        }
}
