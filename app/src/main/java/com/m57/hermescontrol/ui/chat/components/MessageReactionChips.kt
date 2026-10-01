package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.MessageReaction

/**
 * Tapback chips shown under a message. One reaction per author, so a short row is enough;
 * renders nothing when [reactions] is empty.
 */
@Composable
fun MessageReactionChips(
    reactions: List<MessageReaction>,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    Row(
        modifier = modifier.testTag("message_reactions"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
            ) {
                Text(
                    text = reaction.emoji,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
