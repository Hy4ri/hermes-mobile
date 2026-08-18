package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage

/**
 * Friendly label resource for a `display_kind` timeline marker (issue #904).
 * Returns `null` for kinds we don't have a label for — the caller falls back
 * to the generic system-event rendering (raw content) instead of inventing
 * copy for a marker we don't understand.
 */
internal fun timelineMarkerLabelRes(kind: String?): Int? =
    when (kind) {
        "model_switch" -> R.string.timeline_marker_model_switch
        "personality_switch" -> R.string.timeline_marker_personality_switch
        "auto_continue" -> R.string.timeline_marker_auto_continue
        "async_delegation_complete" -> R.string.timeline_marker_delegation_complete
        "skill_invocation" -> R.string.timeline_marker_skill_invocation
        "internal_notification" -> R.string.timeline_marker_internal_notification
        else -> null
    }

/**
 * Model name parsed from a model-switch marker's content, e.g.
 * `[System: The active model for this chat has changed to gpt-5 via provider
 * openai. ...]` → `gpt-5`. The marker text is model-facing scaffolding — the
 * chip shows the human-meaningful name instead of the whole block. Interior
 * dots are kept (`gpt-4.5`); a sentence-ending dot is stripped.
 */
internal fun markerModelFromContent(content: String): String? {
    val raw = MARKER_MODEL_REGEX.find(content)?.groupValues?.getOrNull(1) ?: return null
    val model = raw.removeSuffix(".").trim()
    return model.takeIf { it.isNotBlank() }
}

private val MARKER_MODEL_REGEX = Regex("""changed to ([A-Za-z0-9_.\-]+)""")

/**
 * Centered timeline chip for a `display_kind` marker (issue #904): model
 * switches, personality switches and auto-continues render as a small
 * divider-style chip instead of a fake user bubble. Known kinds get friendly
 * copy; the model-switch chip names the model when the marker content
 * carries it.
 */
@Composable
internal fun TimelineMarkerChip(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val labelRes = timelineMarkerLabelRes(message.displayKind) ?: return
    val text =
        if (message.displayKind == "model_switch") {
            val model = markerModelFromContent(message.content)
            if (model != null) {
                stringResource(R.string.timeline_marker_model_switch_to, model)
            } else {
                stringResource(R.string.timeline_marker_model_switch)
            }
        } else {
            stringResource(labelRes)
        }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.testTag("timeline_marker"),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
