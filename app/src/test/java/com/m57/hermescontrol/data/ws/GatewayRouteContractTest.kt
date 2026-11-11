package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every typed RPC, encoded the way the app really sends it, on both routes, against the vendored
 * contract: the legacy route against the shared schemas, the session authority against its canonical
 * ones. The two differ (e.g. `approval.respond`, `session.stats`/`usage`), so a method that matches only
 * one of them breaks on the other route (#1802). Each method is built twice: with only its required
 * fields and with every field.
 */
@OptIn(ExperimentalSerializationApi::class)
class GatewayRouteContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sample(
        descriptor: SerialDescriptor,
        name: String,
        full: Boolean,
    ): JsonElement =
        when {
            descriptor.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            descriptor.kind == PrimitiveKind.STRING -> JsonPrimitive(if (name.startsWith("session")) SESSION else "x")
            descriptor.kind is PrimitiveKind -> JsonPrimitive(1)
            descriptor.kind == StructureKind.LIST -> JsonArray(emptyList())
            descriptor.kind == StructureKind.MAP -> JsonObject(emptyMap())
            descriptor.kind == StructureKind.CLASS -> sampleObject(descriptor, full)
            else -> JsonNull
        }

    private fun sampleObject(
        descriptor: SerialDescriptor,
        full: Boolean,
    ): JsonObject =
        JsonObject(
            (0 until descriptor.elementsCount)
                .filter { full || !descriptor.isElementOptional(it) }
                .associate {
                    val name = descriptor.getElementName(it)
                    name to sample(descriptor.getElementDescriptor(it), name, full)
                },
        )

    private fun <P, R> wire(
        method: RpcMethod<P, R>,
        authority: Boolean,
        full: Boolean,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<String, Set<String>>? {
        val channel =
            RpcChannel(
                scope = scope,
                json = json,
                sendRequest = { _, _, _ -> "" },
                sendFrame = { true },
                removeQueuedMessage = {},
                onIdleCheck = {},
            )
        if (authority) {
            val tracker =
                ReplayTracker(scope, {
                    com.m57.hermescontrol.data.ws.contract
                        .SessionEventsSinceResult()
                }, {})

            fun frame(text: String) = channel.handleIncomingFrame(text, tracker) {}
            frame("""{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
            frame(
                """{"method":"event","params":{"type":"session.info","session_id":"$SESSION","seq":1,""" +
                    """"payload":{"revision":1,"execution_generation":1}}}""",
            )
        }
        val params =
            try {
                json.decodeFromJsonElement(method.params, sampleObject(method.params.descriptor, full))
            } catch (_: Exception) {
                return null
            }
        val request = channel.createRequest(method.name, channel.encodeParams(method, params))
        return request.method to request.params.keys
    }

    @Test
    fun everyTypedRpcMatchesTheContractOnBothRoutes() =
        runTest {
            val problems = mutableListOf<String>()
            val hitGaps = mutableSetOf<String>()
            var checked = 0
            for (method in RpcMethods.all) {
                for (authority in listOf(false, true)) {
                    for (full in listOf(false, true)) {
                        val (wireMethod, keys) = wire(method, authority, full, this) ?: continue
                        checked++
                        val gap = "${method.name}|${if (authority) "authority" else "legacy"}"
                        val found = GatewayContract.routeParamProblems(wireMethod, keys, authority)
                        if (gap in KNOWN_GAPS) {
                            if (found.isNotEmpty()) hitGaps += gap
                        } else {
                            found.mapTo(problems) { "$it [${if (full) "all fields" else "required only"}]" }
                        }
                    }
                }
            }
            println("route contract: $checked method/route/shape frames checked")
            assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
            val stale = KNOWN_GAPS.keys - hitGaps
            assertTrue("KNOWN_GAPS entries that no longer mismatch; remove them: $stale", stale.isEmpty())
        }

    private companion object {
        const val SESSION = "s1"

        /** `method|route` pairs that mismatch by design, each with the reason it cannot reach the gateway. */
        val KNOWN_GAPS =
            mapOf(
                "prompt.submit|legacy" to
                    "clarify_batch is only sent on the authority route with the capability (see GatewayContractFramesTest)",
                "approval.respond|authority" to
                    "the required-only sample has no request id; ChatViewModel always supplies one",
                "groups.retry|authority" to
                    "member_id and execution_generation come from the task, which always carries both",
                "prompt.cancel|legacy" to "canonical-only method; the app never calls it",
                "prompt.receipt|legacy" to "canonical-only method; the app never calls it",
                "session.export|legacy" to "canonical-only method; a legacy gateway answers method-not-found",
            )
    }
}
