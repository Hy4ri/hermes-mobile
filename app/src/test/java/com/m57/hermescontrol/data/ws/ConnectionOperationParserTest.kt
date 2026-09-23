package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectionTargetAction
import com.m57.hermescontrol.data.model.ConnectionTargetKind
import com.m57.hermescontrol.data.model.ConnectionTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOperationParserTest {
    @Test
    fun parseCompleteSnapshot_preservesBackendOrderAndMetadata() {
        val snapshot = ConnectionOperationParser.parse(completePayload(), "runtime-session")

        assertNotNull(snapshot)
        snapshot!!
        assertEquals("runtime-session", snapshot.sessionId)
        assertEquals("op-1218", snapshot.opId)
        assertEquals(7L, snapshot.seq)
        assertEquals(2, snapshot.targets.size)
        val target = snapshot.targets.first()
        assertEquals("reports", target.name)
        assertEquals(ConnectionTargetKind.MCP, target.kind)
        assertEquals(ConnectionTargetAction.INSTALL, target.action)
        assertEquals(ConnectionTargetState.PENDING, target.state)
        assertEquals("install-2", target.attempt)
        assertEquals("Use a workspace token", target.hint)
        assertEquals(listOf("REGION", "API_TOKEN"), target.requiredEnv.map { it.name })
        assertEquals("us-east", target.requiredEnv[0].defaultValue)
        assertNull(target.requiredEnv[1].defaultValue)
        assertTrue(target.requiredEnv[1].secret)
        assertEquals(listOf("search", "export"), target.tools)
    }

    @Test
    fun unknownFieldsAndEnumValues_degradeWithoutDroppingUsableTarget() {
        val payload =
            completePayload().toMutableMap().apply {
                put("future_operation_field", mapOf("nested" to true))
                put(
                    "targets",
                    listOf(
                        targetPayload().toMutableMap().apply {
                            put("kind", "future-kind")
                            put("action", "future-action")
                            put("state", "future-state")
                            put("future_target_field", "ignored")
                        },
                    ),
                )
            }

        val target = ConnectionOperationParser.parse(payload, "runtime-session")!!.targets.single()

        assertEquals(ConnectionTargetKind.UNKNOWN, target.kind)
        assertEquals(ConnectionTargetAction.UNKNOWN, target.action)
        assertEquals(ConnectionTargetState.UNKNOWN, target.state)
    }

    @Test
    fun malformedIdentityOrDeadline_isRejected() {
        assertNull(ConnectionOperationParser.parse(completePayload() - "op_id", "runtime-session"))
        assertNull(ConnectionOperationParser.parse(completePayload() - "seq", "runtime-session"))
        assertNull(ConnectionOperationParser.parse(completePayload() - "deadline_at", "runtime-session"))
        assertNull(ConnectionOperationParser.parse(completePayload() + ("seq" to 1.5), "runtime-session"))
        assertNull(
            ConnectionOperationParser.parse(completePayload() + ("deadline_at" to Double.NaN), "runtime-session"),
        )
        assertNull(
            ConnectionOperationParser.parse(completePayload() + ("targets" to emptyList<Any>()), "runtime-session"),
        )
    }

    @Test
    fun secretDefaultsAndOAuthUrls_areNeverExposedByModels() {
        val canary = "issue1218-secret-canary"
        val payload =
            completePayload() +
                (
                    "targets" to
                        listOf(
                            targetPayload() +
                                (
                                    "connect_url" to
                                        "https://example.com/oauth?token=$canary"
                                ) +
                                (
                                    "required_env" to
                                        listOf(
                                            mapOf(
                                                "name" to "API_TOKEN",
                                                "required" to true,
                                                "secret" to true,
                                                "default" to canary,
                                            ),
                                        )
                                ),
                        )
                )

        val snapshot = ConnectionOperationParser.parse(payload, "runtime-session")!!

        assertNull(
            snapshot.targets
                .single()
                .requiredEnv
                .single()
                .defaultValue,
        )
        assertFalse(snapshot.toString().contains(canary))
        assertTrue(snapshot.toString().contains("[REDACTED]"))
    }

    private fun completePayload(): Map<String, Any?> =
        mapOf(
            "op_id" to "op-1218",
            "seq" to 7L,
            "deadline_at" to 2_000_000_000.0,
            "timeout_seconds" to 300.0,
            "tool_call_id" to "tool-1",
            "settled" to false,
            "targets" to
                listOf(
                    targetPayload(),
                    mapOf(
                        "name" to "github",
                        "kind" to "connector",
                        "action" to "authorize",
                        "state" to "initiated",
                    ),
                ),
        )

    private fun targetPayload(): Map<String, Any?> =
        mapOf(
            "name" to "reports",
            "kind" to "mcp",
            "action" to "install",
            "state" to "pending",
            "detail" to "Credentials required",
            "instructions" to "Enter the service credentials",
            "discovery_error" to null,
            "connect_url" to null,
            "connection_id" to "vendor-account-1",
            "attempt" to "install-2",
            "hint" to "Use a workspace token",
            "required_env" to
                listOf(
                    mapOf(
                        "name" to "REGION",
                        "required" to false,
                        "secret" to false,
                        "default" to "us-east",
                        "prompt" to "Region",
                    ),
                    mapOf(
                        "name" to "API_TOKEN",
                        "required" to true,
                        "secret" to true,
                        "default" to "must-not-be-retained",
                        "prompt" to "API token",
                    ),
                ),
            "tools" to listOf("search", "export"),
        )
}
