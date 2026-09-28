package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.notification.ReplyNotificationTarget
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #1337: the REST mapper runs on plain inputs, with no Android, AuthManager, or tracker state. */
class ChatServerMessageMapperPurityTest {
    private fun assistant(
        id: Int,
        text: String,
    ) = SessionMessage(id = id, role = "assistant", content = JsonPrimitive(text))

    @Test
    fun mediaAttachmentsUseTheInjectedUrlBuilder() {
        val mapped =
            mapServerMessages(
                "s",
                listOf(assistant(1, "Chart MEDIA:/opt/hermes/chart.png")),
                0,
                true,
                emptyList(),
                mediaUrl = { path -> "gw://$path" },
            ).single()

        assertEquals("Chart", mapped.content.trim())
        assertEquals(listOf("gw:///opt/hermes/chart.png"), mapped.attachments?.map { it.uri })
    }

    @Test
    fun mediaWithoutAGatewayStripsTheTokenAndAddsNoAttachment() {
        val mapped =
            mapServerMessages("s", listOf(assistant(1, "Chart MEDIA:/opt/hermes/chart.png")), 0, true, emptyList())
                .single()

        assertEquals("Chart", mapped.content.trim())
        assertNull(mapped.attachments)
    }

    @Test
    fun activeReplyTargetIsAPlainInputScopedToItsSession() {
        val rows = listOf(assistant(100, "Done"), assistant(200, "Done"))
        val target = ReplyNotificationTarget("default", "s", "comp", 1L, serverMessageId = 100)

        val bound = mapServerMessages("s", rows, 0, true, emptyList(), activeReplyTarget = target)
        val otherSession =
            mapServerMessages("s", rows, 0, true, emptyList(), activeReplyTarget = target.copy(sessionId = "x"))

        assertEquals(listOf("comp", null), bound.map { it.completionId })
        assertEquals(listOf(null, null), otherSession.map { it.completionId })
    }

    @Test
    fun rowsWithoutTimestampsUseTheInjectedClock() {
        val mapped = mapServerMessages("s", listOf(assistant(1, "Hi")), 0, true, emptyList(), nowMs = 42L)

        assertEquals(42L, mapped.single().timestamp)
    }
}
