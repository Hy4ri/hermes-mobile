package com.m57.hermescontrol.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
        val encoded = json.encodeToString(PruneRequest(olderThanDays = 7, includeArchived = true, profile = "work"))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("7", body["older_than_days"]!!.jsonPrimitive.content)
        assertEquals("work", body["profile"]!!.jsonPrimitive.content)
        // The old key must be gone: the backend ignores it.
        assertFalse(body.containsKey("days"))
    }

    @Test
    fun pruneRequest_omitsProfileWhenUnnamed() {
        val encoded = json.encodeToString(PruneRequest(olderThanDays = 30, includeArchived = true))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("30", body["older_than_days"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("profile"))
    }

    @Test
    fun pruneRequest_alwaysSendsIncludeArchived() {
        // Json drops default values, so this key has no Kotlin default: an omitted
        // include_archived means the backend skips archived rows and, on an
        // auto-archiving store, deletes nothing at all.
        val included =
            json
                .parseToJsonElement(
                    json.encodeToString(PruneRequest(olderThanDays = 30, includeArchived = true)),
                ).jsonObject
        val excluded =
            json
                .parseToJsonElement(
                    json.encodeToString(PruneRequest(olderThanDays = 30, includeArchived = false)),
                ).jsonObject

        assertEquals("true", included["include_archived"]!!.jsonPrimitive.content)
        assertEquals("false", excluded["include_archived"]!!.jsonPrimitive.content)
    }

    @Test
    fun pruneRequest_dryRunIsOnlySentWhenAsked() {
        val preview =
            json
                .parseToJsonElement(
                    json.encodeToString(PruneRequest(olderThanDays = 30, includeArchived = true, dryRun = true)),
                ).jsonObject
        val real =
            json
                .parseToJsonElement(
                    json.encodeToString(PruneRequest(olderThanDays = 30, includeArchived = true)),
                ).jsonObject

        assertEquals("true", preview["dry_run"]!!.jsonPrimitive.content)
        assertFalse(real.containsKey("dry_run"))
    }

    @Test
    fun pruneResponse_decodesDryRunCandidates() {
        val decoded =
            json.decodeFromString<PruneResponse>(
                """{"ok":true,"removed":0,"matched":2,"skipped_open":1,
                   "sessions":[{"id":"s1","title":"a","source":"desktop"},{"id":"s2"}]}""",
            )

        assertEquals(0, decoded.removed)
        assertEquals(2, decoded.matched)
        assertEquals(1, decoded.skippedOpen)
        assertEquals(listOf("s1", "s2"), decoded.sessions.map { it.id })
    }

    @Test
    fun pruneResponse_decodesRealPruneCounts() {
        val decoded = json.decodeFromString<PruneResponse>("""{"ok":true,"removed":12,"skipped_open":2}""")

        assertEquals(12, decoded.removed)
        assertEquals(2, decoded.skippedOpen)
        assertEquals(emptyList<PruneCandidate>(), decoded.sessions)
    }

    @Test
    fun mutationSnapshot_decodesIntAndStringCounters() {
        // Live backends answer ints; older rows carry strings. Both must parse so
        // the fenced delete can quote a revision instead of dying on a 409.
        val ints =
            json.decodeFromString<SessionMutationSnapshot>(
                """{"session_id":"s1","exists":true,"runtime_revision":7,"runtime_generation":3}""",
            )
        val strings =
            json.decodeFromString<SessionMutationSnapshot>(
                """{"session_id":"s2","exists":true,"runtime_revision":"7","runtime_generation":"3"}""",
            )

        assertEquals("7", ints.runtimeRevision?.jsonPrimitive?.contentOrNull)
        assertEquals("3", ints.runtimeGeneration?.jsonPrimitive?.contentOrNull)
        assertEquals("7", strings.runtimeRevision?.jsonPrimitive?.contentOrNull)
        assertEquals("3", strings.runtimeGeneration?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun mutationSnapshot_missingRowStillCarriesRevisionZero() {
        val decoded =
            json.decodeFromString<SessionMutationSnapshot>(
                """{"session_id":"gone","exists":false,"runtime_revision":0,"runtime_generation":null}""",
            )

        assertEquals(false, decoded.exists)
        assertEquals("0", decoded.runtimeRevision?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun bulkDeleteRequest_carriesProfile() {
        val encoded = json.encodeToString(BulkDeleteRequest(ids = listOf("a", "b"), profile = "work"))
        val body = json.parseToJsonElement(encoded).jsonObject

        assertEquals("work", body["profile"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun bulkDeleteResponse_decodesSkippedActive() {
        val decoded =
            json.decodeFromString<BulkDeleteResponse>(
                """{"ok":true,"deleted":1,"skipped_active":["a"]}""",
            )

        assertEquals(1, decoded.deleted)
        assertEquals(listOf("a"), decoded.skippedActive)
    }

    @Test
    fun bulkDeleteResponse_olderGatewayHasNoSkippedActive() {
        val decoded = json.decodeFromString<BulkDeleteResponse>("""{"ok":true,"deleted":2}""")

        assertEquals(emptyList<String>(), decoded.skippedActive)
    }
}
