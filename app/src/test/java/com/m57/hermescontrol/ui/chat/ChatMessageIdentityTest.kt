package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.canonicalMessageOrder
import com.m57.hermescontrol.ui.chat.fullbleed.isTimelineMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1336: one owner for the REST row-id format and the known display kinds. */
class ChatMessageIdentityTest {
    @Test
    fun restIdsRoundTripThroughTheSingleFormatOwner() {
        val id = RestMessageId.of("s-1", 42)

        assertEquals("rest-s-1-42", id)
        assertTrue(RestMessageId.isRest(id))
        assertFalse(RestMessageId.isRest("ws-42"))
        assertEquals(42L, canonicalMessageOrder(id, "s-1"))
        assertNull(canonicalMessageOrder(RestMessageId.of("other", 42), "s-1"))
        assertEquals(id, ChatMessage(id = id, role = MessageRole.USER, content = "").canonicalRestId)
    }

    @Test
    fun onlyUnknownOrMarkerKindsAreTimelineMarkers() {
        fun kind(value: String?) = ChatMessage(role = MessageRole.USER, content = "x", displayKind = value)

        DisplayKind.nonMarkerKinds.forEach { assertFalse(it, kind(it).isTimelineMarker()) }
        assertTrue(kind(DisplayKind.MODEL_SWITCH).isTimelineMarker())
        assertTrue(kind("future_backend_kind").isTimelineMarker())
        assertFalse(kind(null).isTimelineMarker())
    }
}
