package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanDetailEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KanbanEventFormatterTest {
    @Test
    fun testCreatedEvent() {
        val payload =
            buildJsonObject {
                put("status", "ready")
                put("assignee", "coder")
            }
        val event = KanbanDetailEvent(id = 1, kind = "created", payload = payload, createdAt = 1000)
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Created in Ready by coder", formatted.label)
        assertNull(formatted.detail)
    }

    @Test
    fun testStatusEventWithReason() {
        val payload =
            buildJsonObject {
                put("status", "done")
                put("reason", "completed successfully")
            }
        val event = KanbanDetailEvent(id = 2, kind = "status", payload = payload, createdAt = 1000)
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Moved to Done", formatted.label)
        assertEquals("completed successfully", formatted.detail)
    }

    @Test
    fun testParentReopenedStatusEvent() {
        val payload =
            buildJsonObject {
                put("status", "todo")
                put("reason", "parent_reopened")
                put("parent", "t_parent1")
            }
        val event = KanbanDetailEvent(id = 3, kind = "status", payload = payload, createdAt = 1000)
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Moved to Todo", formatted.label)
        assertEquals("Parent t_parent1 reopened", formatted.detail)
    }

    @Test
    fun testAssignedEvent() {
        val payload = buildJsonObject { put("assignee", "reviewer") }
        val event = KanbanDetailEvent(id = 4, kind = "assigned", payload = payload, createdAt = 1000)
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Assigned to reviewer", formatted.label)

        val unassignedEvent = KanbanDetailEvent(id = 5, kind = "assigned", payload = null, createdAt = 1000)
        assertEquals("Unassigned", KanbanEventFormatter.format(unassignedEvent).label)
    }

    @Test
    fun testClaimedReviewAndWorker() {
        val reviewClaim =
            KanbanDetailEvent(
                id = 6,
                kind = "claimed",
                payload = buildJsonObject { put("source_status", "review") },
                createdAt = 1000,
            )
        assertEquals("Claimed for review", KanbanEventFormatter.format(reviewClaim).label)

        val workerClaim =
            KanbanDetailEvent(
                id = 7,
                kind = "claimed",
                payload = buildJsonObject { put("source_status", "ready") },
                createdAt = 1000,
            )
        assertEquals("Claimed by worker", KanbanEventFormatter.format(workerClaim).label)
    }

    @Test
    fun testSpawnedEvent() {
        val event =
            KanbanDetailEvent(
                id = 8,
                kind = "spawned",
                payload = buildJsonObject { put("pid", 12345) },
                createdAt = 1000,
            )
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Worker started", formatted.label)
        assertEquals("PID 12345", formatted.detail)
    }

    @Test
    fun testLifecycleEvents() {
        assertEquals("Completed", KanbanEventFormatter.format(KanbanDetailEvent(9, "completed", null, 1000)).label)
        assertEquals(
            "Blocked",
            KanbanEventFormatter
                .format(
                    KanbanDetailEvent(
                        10,
                        "blocked",
                        buildJsonObject { put("reason", "waiting on CI") },
                        1000,
                    ),
                ).label,
        )
        assertEquals(
            "Unblocked (Ready)",
            KanbanEventFormatter
                .format(
                    KanbanDetailEvent(
                        11,
                        "unblocked",
                        buildJsonObject { put("status", "ready") },
                        1000,
                    ),
                ).label,
        )
        assertEquals(
            "Reclaimed",
            KanbanEventFormatter
                .format(
                    KanbanDetailEvent(
                        12,
                        "reclaimed",
                        buildJsonObject { put("reason", "timeout") },
                        1000,
                    ),
                ).label,
        )
        assertEquals("Specified", KanbanEventFormatter.format(KanbanDetailEvent(13, "specified", null, 1000)).label)
        assertEquals(
            "Promoted to ready",
            KanbanEventFormatter.format(KanbanDetailEvent(14, "promoted", null, 1000)).label,
        )
        assertEquals("Archived", KanbanEventFormatter.format(KanbanDetailEvent(15, "archived", null, 1000)).label)
        assertEquals(
            "Priority set to 2",
            KanbanEventFormatter
                .format(
                    KanbanDetailEvent(
                        16,
                        "reprioritized",
                        buildJsonObject { put("priority", 2) },
                        1000,
                    ),
                ).label,
        )
    }

    @Test
    fun testUnknownEventWithScalarPayload() {
        val payload =
            buildJsonObject {
                put("foo", "bar")
                put("count", 42)
            }
        val event = KanbanDetailEvent(id = 17, kind = "custom_hook", payload = payload, createdAt = 1000)
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Custom hook", formatted.label)
        assertEquals("foo=bar count=42", formatted.detail)
    }

    @Test
    fun testStringJsonPayload() {
        val event =
            KanbanDetailEvent(
                id = 18,
                kind = "status",
                payload = JsonPrimitive("{\"status\":\"blocked\",\"reason\":\"rate limit\"}"),
                createdAt = 1000,
            )
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Moved to Blocked", formatted.label)
        assertEquals("rate limit", formatted.detail)
    }

    @Test
    fun testMalformedPayloadGraceful() {
        val event =
            KanbanDetailEvent(
                id = 19,
                kind = "custom_test",
                payload = JsonPrimitive("{not valid json"),
                createdAt = 1000,
            )
        val formatted = KanbanEventFormatter.format(event)
        assertEquals("Custom test", formatted.label)
        assertEquals("raw={not valid json", formatted.detail)
    }
}
