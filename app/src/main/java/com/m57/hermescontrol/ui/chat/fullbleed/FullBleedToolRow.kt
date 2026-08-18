package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.chat.ChatMessage

/**
 * Tool-row treatment inside the full-bleed renderer (issue #866, redone for
 * desktop parity).
 *
 * The old [com.m57.hermescontrol.ui.chat.ToolBubble] Card is gone — tool calls
 * now render as inline, always-expanded rows (1:1 with the desktop app's
 * `ToolEntry`), folded into the agent turn in original order. [InlineToolRow]
 * owns the entire treatment; this wrapper only supplies the 16dp full-bleed
 * indent so tool rows align with the prose margin.
 */
@Composable
internal fun FullBleedToolRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp)
                .testTag("fullbleed_tool_row"),
    ) {
        InlineToolRow(message)
    }
}
