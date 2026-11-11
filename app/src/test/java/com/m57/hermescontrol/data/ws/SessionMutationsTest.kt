package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMutationsTest {
    @Test
    fun bothBranchMethodsCarryTitleAndDurableBoundaryAndRequireBothFences() {
        for (method in listOf(WsMethods.SESSION_BRANCH, WsMethods.SESSION_BRANCH_WHOLE)) {
            val mutations = SessionMutations()
            val params =
                buildJsonObject {
                    put("session_id", "parent")
                    put("name", "Child")
                    put("through_message_id", 42)
                }
            assertNull(mutations.prepare(method, params, 3))
            mutations.adoptEvent("session.info", "parent", buildJsonObject { put("revision", 7) })
            assertNull(mutations.prepare(method, params, null))
            val first = requireNotNull(mutations.prepare(method, params, 3))
            assertEquals(JsonPrimitive("branch"), first["operation"])
            assertEquals(JsonPrimitive(7), first["expected_revision"])
            assertEquals(JsonPrimitive(3), first["expected_generation"])
            assertEquals(
                buildJsonObject {
                    put("title", "Child")
                    put("through_message_id", 42)
                },
                first["payload"],
            )
            // A generation update cannot change an uncertain request: retry its exact receipt identity.
            assertEquals(first, mutations.prepare(method, params, 4))
            mutations.track("rpc1", first)
            mutations.settle(
                "rpc1",
                null,
                buildJsonObject {
                    put("reason", "session_busy")
                    put("revision", 8)
                },
            )
            val retry = requireNotNull(mutations.prepare(method, params, 4))
            assertNotEquals(first["request_id"], retry["request_id"])
            assertEquals(JsonPrimitive(8), retry["expected_revision"])
            assertEquals(JsonPrimitive(4), retry["expected_generation"])
        }
    }

    @Test
    fun countOnlyBoundaryCannotSilentlyCopyWholeHistory() {
        val mutations = SessionMutations()
        mutations.adoptEvent("session.info", "parent", buildJsonObject { put("revision", 1) })
        for (boundary in listOf(null, JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("42"), JsonPrimitive(1.5))) {
            val params =
                buildJsonObject {
                    put("session_id", "parent")
                    put("count", 3)
                    boundary?.let { put("through_message_id", it) }
                }
            val failure = runCatching { mutations.prepare(WsMethods.SESSION_BRANCH, params, 0) }.exceptionOrNull()
            assertTrue("count needs a positive durable row id: $boundary", failure is IllegalArgumentException)
        }
        val request =
            requireNotNull(
                mutations.prepare(
                    WsMethods.SESSION_BRANCH,
                    buildJsonObject {
                        put("session_id", "parent")
                        put("count", 3)
                        put("through_message_id", 42)
                    },
                    0,
                ),
            )
        assertEquals(buildJsonObject { put("through_message_id", 42) }, request["payload"])
    }

    @Test
    fun unnamedWholeBranchCopiesAllHistoryWithEmptyPayload() {
        val mutations = SessionMutations()
        mutations.adoptEvent("session.info", "parent", buildJsonObject { put("revision", 1) })
        val request =
            requireNotNull(
                mutations.prepare(
                    WsMethods.SESSION_BRANCH_WHOLE,
                    buildJsonObject { put("session_id", "parent") },
                    0,
                ),
            )
        assertEquals(buildJsonObject {}, request["payload"])
    }
}
