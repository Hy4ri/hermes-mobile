package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KanbanEventsClientTest {
    @Test
    fun `parses envelope with snake_case fields and cursor`() {
        val frame =
            """{"events":[{"id":7,"task_id":"t-1","run_id":"r-9","kind":"status","payload":{"status":"done"},"created_at":1710000000}],"cursor":7}"""
        val envelope = parseKanbanEventsFrame(frame)
        assertNotNull(envelope)
        assertEquals(7L, envelope!!.cursor)
        assertEquals(1, envelope.events.size)
        val event = envelope.events.first()
        assertEquals(7L, event.id)
        assertEquals("t-1", event.taskId)
        assertEquals("r-9", event.runId)
        assertEquals("status", event.kind)
        assertEquals("done", event.payload?.get("status")?.jsonPrimitive?.content)
        assertEquals(1710000000L, event.createdAt)
    }

    @Test
    fun `parses empty events batch`() {
        val envelope = parseKanbanEventsFrame("""{"events":[],"cursor":3}""")
        assertNotNull(envelope)
        assertEquals(3L, envelope!!.cursor)
        assertEquals(0, envelope.events.size)
    }

    @Test
    fun `null payload tolerated`() {
        val envelope = parseKanbanEventsFrame("""{"events":[{"id":1,"kind":"edited","payload":null}],"cursor":1}""")
        assertNotNull(envelope)
        assertNull(envelope!!.events.first().payload)
    }

    @Test
    fun `malformed frames return null`() {
        assertNull(parseKanbanEventsFrame("not json"))
        assertNull(parseKanbanEventsFrame("""{"events": 42}"""))
        assertNull(parseKanbanEventsFrame(""))
    }
}
