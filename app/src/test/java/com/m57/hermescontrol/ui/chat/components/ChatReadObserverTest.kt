package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadObserverTest {
    private fun createMockItem(
        key: Any,
        offset: Int,
        size: Int,
    ): LazyListItemInfo {
        val item = mockk<LazyListItemInfo>()
        every { item.key } returns key
        every { item.offset } returns offset
        every { item.size } returns size
        return item
    }

    private fun createMockLayoutInfo(
        items: List<LazyListItemInfo>,
        startOffset: Int = 0,
        endOffset: Int = 1000,
    ): LazyListLayoutInfo {
        val layout = mockk<LazyListLayoutInfo>()
        every { layout.visibleItemsInfo } returns items
        every { layout.viewportStartOffset } returns startOffset
        every { layout.viewportEndOffset } returns endOffset
        return layout
    }

    @Test
    fun `returns empty when visible items or messages are empty`() {
        val layout = createMockLayoutInfo(emptyList())
        val msg =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = "Hello",
            )

        assertTrue(ChatReadObserver.findVisibleAssistantMessages(layout, listOf(msg)).isEmpty())

        val layoutWithItem = createMockLayoutInfo(listOf(createMockItem("prose-msg-1", 100, 50)))
        assertTrue(ChatReadObserver.findVisibleAssistantMessages(layoutWithItem, emptyList()).isEmpty())
    }

    @Test
    fun `identifies visible completed assistant messages`() {
        val msg1 =
            ChatMessage(
                id = "msg-1",
                role = MessageRole.ASSISTANT,
                content = "Answer 1",
                completionId = "comp-1",
            )
        val msg2 =
            ChatMessage(
                id = "msg-2",
                role = MessageRole.ASSISTANT,
                content = "Answer 2",
                completionId = "comp-2",
            )

        val items =
            listOf(
                createMockItem("prose-msg-1", 100, 200),
                createMockItem("prose-msg-2", 400, 250),
            )
        val layout = createMockLayoutInfo(items)

        val visible = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(msg1, msg2))
        assertEquals(2, visible.size)
        assertEquals("msg-1", visible[0].id)
        assertEquals("msg-2", visible[1].id)
    }

    @Test
    fun `ignores non-prose items like user bubbles, reasoning cards, and tool rows`() {
        val assistant =
            ChatMessage(
                id = "ast-1",
                role = MessageRole.ASSISTANT,
                content = "Done",
            )
        val user =
            ChatMessage(
                id = "usr-1",
                role = MessageRole.USER,
                content = "Question",
            )

        val items =
            listOf(
                createMockItem("user-usr-1", 50, 100),
                createMockItem("reasoning-ast-1", 150, 100),
                createMockItem("tool-tool-1", 250, 100),
                createMockItem("prose-ast-1", 350, 100),
            )
        val layout = createMockLayoutInfo(items)

        val visible = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(assistant, user))
        assertEquals(1, visible.size)
        assertEquals("ast-1", visible.single().id)
    }

    @Test
    fun `ignores streaming assistant messages`() {
        val streaming =
            ChatMessage(
                id = "stream-1",
                role = MessageRole.ASSISTANT,
                content = "typing...",
                isStreaming = true,
            )

        val items = listOf(createMockItem("prose-stream-1", 100, 200))
        val layout = createMockLayoutInfo(items)

        val visible = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(streaming))
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `filters out items outside viewport bounds`() {
        val msgAbove =
            ChatMessage(
                id = "above",
                role = MessageRole.ASSISTANT,
                content = "Above viewport",
            )
        val msgInside =
            ChatMessage(
                id = "inside",
                role = MessageRole.ASSISTANT,
                content = "Inside viewport",
            )
        val msgBelow =
            ChatMessage(
                id = "below",
                role = MessageRole.ASSISTANT,
                content = "Below viewport",
            )

        // Viewport is 200..800
        val items =
            listOf(
                // Fully above: offset -100, size 100 -> offset + size = 0 <= 200
                createMockItem("prose-above", -100, 100),
                // Inside: offset 300, size 150
                createMockItem("prose-inside", 300, 150),
                // Fully below: offset 900, size 100 >= 800
                createMockItem("prose-below", 900, 100),
            )
        val layout = createMockLayoutInfo(items, startOffset = 200, endOffset = 800)

        val visible = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(msgAbove, msgInside, msgBelow))
        assertEquals(1, visible.size)
        assertEquals("inside", visible.single().id)
    }

    @Test
    fun `includes partially visible messages at viewport edges`() {
        val topPartial =
            ChatMessage(
                id = "top",
                role = MessageRole.ASSISTANT,
                content = "Top edge",
            )
        val bottomPartial =
            ChatMessage(
                id = "bottom",
                role = MessageRole.ASSISTANT,
                content = "Bottom edge",
            )

        // Viewport 100..500
        val items =
            listOf(
                // Crosses top boundary: offset 50, size 100 (bottom is 150 > 100)
                createMockItem("prose-top", 50, 100),
                // Crosses bottom boundary: offset 450, size 100 (top is 450 < 500)
                createMockItem("prose-bottom", 450, 100),
            )
        val layout = createMockLayoutInfo(items, startOffset = 100, endOffset = 500)

        val visible = ChatReadObserver.findVisibleAssistantMessages(layout, listOf(topPartial, bottomPartial))
        assertEquals(2, visible.size)
        assertEquals("top", visible[0].id)
        assertEquals("bottom", visible[1].id)
    }
}
