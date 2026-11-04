package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedMemberActivityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old gateways and nullable fields do not imply completed work`() {
        assertNull(json.decodeFromString<HostedDriverStatus>("{}").memberActivity)
        assertNull(json.decodeFromString<HostedDriverStatus>("""{"member_activity":null}""").memberActivity)
        val empty = json.decodeFromString<HostedDriverStatus>("""{"member_activity":[]}""")
        assertTrue(empty.memberActivity!!.isEmpty())
        assertNull(empty.maxRounds)
    }

    @Test
    fun `decode all backend states and preserve fractional second timestamps`() {
        val status =
            json.decodeFromString<HostedDriverStatus>(
                """{
              "max_rounds":8,
              "member_activity":[
                {"member_id":"a","status":"queued"},
                {"member_id":"b","status":"running","thread_id":"thread-1","round_index":0,
                 "started_at":1700000000.25,"tool":"terminal","progress_at":1700000001.75},
                {"member_id":"c","status":"stopping","thread_id":null,"round_index":null,
                 "started_at":null,"tool":null,"progress_at":null}
              ]
            }""",
            )
        assertEquals(8, status.maxRounds)
        val rows = status.memberActivity!!
        assertEquals(listOf("queued", "running", "stopping"), rows.map { it.status })
        assertNull(rows[0].startedAt)
        assertNull(rows[0].tool)
        assertEquals("thread-1", rows[1].threadId)
        assertEquals(0, rows[1].roundIndex)
        assertEquals(1700000000.25, rows[1].startedAt!!, 0.0)
        assertEquals(1700000001.75, rows[1].progressAt!!, 0.0)
        assertEquals("terminal", rows[1].tool)
        assertNull(rows[2].tool)
        assertNull(rows[2].startedAt)
        assertNull(rows[2].progressAt)
    }
}
