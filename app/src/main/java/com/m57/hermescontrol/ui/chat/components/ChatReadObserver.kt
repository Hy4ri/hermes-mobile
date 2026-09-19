package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole

object ChatReadObserver {
    /**
     * Inspects the LazyColumn's visible items to determine which completed assistant
     * messages currently have any portion visible within the viewport.
     *
     * In [com.m57.hermescontrol.ui.chat.fullbleed.FullBleedChatList], assistant prose
     * bubbles are keyed with `"prose-${message.id}"`. An item is visible when its top
     * edge is before the viewport end offset AND its bottom edge (offset + size) is past
     * the viewport start offset.
     */
    fun findVisibleAssistantMessages(
        layoutInfo: LazyListLayoutInfo,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || messages.isEmpty()) return emptyList()

        val start = layoutInfo.viewportStartOffset
        val end = layoutInfo.viewportEndOffset
        val messageMap = messages.associateBy { it.id }

        return visible
            .filter { it.offset + it.size > start && it.offset < end }
            .mapNotNull { item ->
                val key = item.key as? String ?: return@mapNotNull null
                if (key.startsWith("prose-")) {
                    val msgId = key.removePrefix("prose-")
                    val msg = messageMap[msgId]
                    if (msg != null && msg.role == MessageRole.ASSISTANT && !msg.isStreaming) {
                        msg
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
    }
}
