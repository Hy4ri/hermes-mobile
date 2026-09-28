package com.m57.hermescontrol.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Wire-shape pins for the destructive session ops. The backend reads the
 * profile off these request BODIES (`SessionPrune` / `BulkDeleteSessions`) —
 * the `?profile=` query rewrite never reaches them — and its prune model is
 * `older_than_days` (the old `days` key was silently ignored, leaving the
 * server to prune its own 90-day default).
 */
class SessionDestructiveOpsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun pruneRequest_sendsServerFieldNames() {
        val encoded = json.encodeToString(PruneRequest(olderThanDays = 7, profile = "work"))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("7", body["older_than_days"]!!.jsonPrimitive.content)
        assertEquals("work", body["profile"]!!.jsonPrimitive.content)
        // The old key must be gone: the backend ignores it.
        assertFalse(body.containsKey("days"))
    }

    @Test
    fun pruneRequest_omitsProfileWhenUnnamed() {
        val encoded = json.encodeToString(PruneRequest(olderThanDays = 30))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("30", body["older_than_days"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("profile"))
    }

    @Test
    fun bulkDeleteRequest_carriesProfile() {
        val encoded = json.encodeToString(BulkDeleteRequest(ids = listOf("a", "b"), profile = "work"))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("work", body["profile"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
