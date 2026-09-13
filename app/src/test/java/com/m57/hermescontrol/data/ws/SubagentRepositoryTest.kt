package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.SubagentListItem
import com.m57.hermescontrol.data.model.SubagentListResponse
import com.m57.hermescontrol.data.model.SubagentTailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRepositoryTest {
    @Test
    fun testWsMethodsConstants() {
        assertEquals("subagent.list", WsMethods.SUBAGENT_LIST)
        assertEquals("subagent.tail", WsMethods.SUBAGENT_TAIL)
    }

    @Test
    fun testSubagentListResponse_decoding_fullPayload() {
        val map =
            mapOf(
                "subagents" to
                    listOf(
                        mapOf(
                            "subagent_id" to "sub-001",
                            "goal" to "Scrape web documentation",
                            "status" to "running",
                            "model" to "claude-3-5-sonnet",
                            "elapsed_seconds" to 14.5,
                            "started_at" to 1718000000.0,
                            "parent_id" to "root-session",
                            "depth" to 1,
                            "delegation_id" to "del-123",
                            "tool_count" to 8,
                            "last_tool" to "web_search",
                            "accepting_steer" to true,
                            "unexpected_future_field" to "safely ignored",
                        ),
                    ),
                "delegations" to emptyList<Any>(),
            )

        val response = SubagentRepository.decode<SubagentListResponse>(map)
        assertNotNull(response)
        assertEquals(1, response?.subagents?.size)

        val item = response?.subagents?.first()
        assertNotNull(item)
        assertEquals("sub-001", item?.subagentId)
        assertEquals("Scrape web documentation", item?.goal)
        assertEquals("running", item?.status)
        assertEquals("claude-3-5-sonnet", item?.model)
        assertEquals(14.5, item?.elapsedSeconds)
        assertEquals(1718000000.0, item?.startedAt)
        assertEquals("root-session", item?.parentId)
        assertEquals(1, item?.depth)
        assertEquals("del-123", item?.delegationId)
        assertEquals(8, item?.toolCount)
        assertEquals("web_search", item?.lastTool)
        assertEquals(true, item?.acceptingSteer)
    }

    @Test
    fun testSubagentListResponse_decoding_minimalAndMissingFields() {
        val map =
            mapOf(
                "subagents" to
                    listOf(
                        mapOf(
                            "subagent_id" to "sub-002",
                        ),
                        mapOf<String, Any>(),
                    ),
            )

        val response = SubagentRepository.decode<SubagentListResponse>(map)
        assertNotNull(response)
        assertEquals(2, response?.subagents?.size)
        assertEquals("sub-002", response?.subagents?.get(0)?.subagentId)
        assertNull(response?.subagents?.get(0)?.goal)
        assertEquals("", response?.subagents?.get(1)?.subagentId)
    }

    @Test
    fun testSubagentTailResponse_decoding_gatewayTextVariant() {
        val map =
            mapOf(
                "subagent_id" to "sub-001",
                "available" to true,
                "text" to "Streaming subagent output line 1\nLine 2\n",
                "truncated" to true,
            )

        val response = SubagentRepository.decode<SubagentTailResponse>(map)
        assertNotNull(response)
        assertEquals("sub-001", response?.subagentId)
        assertEquals(true, response?.available)
        assertEquals("Streaming subagent output line 1\nLine 2\n", response?.content())
        assertTrue(response?.truncated == true)
    }

    @Test
    fun testSubagentTailResponse_decoding_contractTailVariant() {
        val map =
            mapOf(
                "subagent_id" to "sub-002",
                "tail" to "Contract tail output snippet",
                "bytes_read" to 4096L,
            )

        val response = SubagentRepository.decode<SubagentTailResponse>(map)
        assertNotNull(response)
        assertEquals("sub-002", response?.subagentId)
        assertEquals("Contract tail output snippet", response?.content())
        assertEquals(4096L, response?.bytesRead)
        assertFalse(response?.truncated == true)
    }

    @Test
    fun testDecode_handlesNullResult() {
        val response = SubagentRepository.decode<SubagentListItem>(null)
        assertNull(response)
    }
}
