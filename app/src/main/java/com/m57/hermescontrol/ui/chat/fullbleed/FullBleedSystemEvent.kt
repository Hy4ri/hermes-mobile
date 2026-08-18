package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.SystemBubble

/**
 * Full-bleed treatment for a system event — delegates to [SystemBubble] (the
 * shared system/tool/system-event renderer) with a full-bleed test tag.
 *
 * Recovered from the pre-refactor [FullBleedToolRow] (issue #866) after the
 * inline-tool-row rework moved the tool treatment out and left this delegation
 * imported by [FullBleedChatList].
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
