package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.SystemBubble
import com.m57.hermescontrol.ui.chat.ToolBubble

/**
 * Tool-row treatment inside the full-bleed renderer (issue #866).
 *
 * ToolBubble is ALREADY a compact dimmed card (surfaceContainerHigh, 8dp
 * radius, collapsed summary with expand/copy/raw-json/risk-chip) — distinct
 * from agent prose by design. In full-bleed mode we reuse it verbatim so the
 * verified tool-rendering logic is never duplicated, adding only a 16dp
 * start indent so tool rows align with the full-bleed prose margin.
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
        ToolBubble(message)
    }
}

/**
 * System-event treatment inside the full-bleed renderer (issue #866).
 *
 * Reuses [SystemBubble] verbatim — it already renders as a centered, dimmed,
 * italic caption (with approval buttons and the Self-improvement review card
 * handling intact), which keeps system events visually distinct from prose.
 */
@Composable
internal fun FullBleedSystemEvent(
    message: ChatMessage,
    onRespondApproval: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBubble(
        message = message,
        onRespondApproval = onRespondApproval,
        modifier = modifier.testTag("fullbleed_system_event"),
    )
}
