package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.LiveWorkCounts
import com.m57.hermescontrol.theme.LocalSpacing

/**
 * Issue #387: live subagents and background processes a conversation still owns (`subagent.roster`).
 * Neutral colors: this is information, not a call for attention; a zero count is not drawn.
 */
@Composable
fun SessionLiveWorkIndicator(
    counts: LiveWorkCounts,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    if (counts.isEmpty) return
    val spacing = LocalSpacing.current
    val parts =
        buildList {
            if (counts.subagents > 0) {
                add(
                    pluralStringResource(
                        R.plurals.sessions_live_work_subagents_desc,
                        counts.subagents,
                        counts.subagents,
                    ),
                )
            }
            if (counts.background > 0) {
                add(
                    pluralStringResource(
                        R.plurals.sessions_live_work_background_desc,
                        counts.background,
                        counts.background,
                    ),
                )
            }
        }
    val description = parts.joinToString(", ")
    val fg = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier =
            modifier
                .testTag("session_live_work_$sessionId")
                .clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (counts.subagents > 0) LiveWorkCount(Icons.Filled.Groups, counts.subagents, fg)
            if (counts.background > 0) LiveWorkCount(Icons.Filled.Terminal, counts.background, fg)
        }
    }
}

@Composable
private fun LiveWorkCount(
    icon: ImageVector,
    count: Int,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
