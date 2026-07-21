package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom toolbar row for the chat composer.
 *
 * Layout: [📎 attach] [model chip] [reasoning chip] [←spacer→] [🎙 mic]
 *
 * Attach and mic callbacks are passed separately so they can move from the
 * current one-row layout into this toolbar without behavioural change.
 * Model and reasoning chips provide quick access to session-level
 * configuration.
 */
@Composable
fun ComposerToolbar(
    isConnected: Boolean,
    currentSessionModel: String?,
    reasoningLevel: String?,
    isListening: Boolean,
    onAttachTap: () -> Unit,
    onModelTap: () -> Unit,
    onReasoningTap: () -> Unit,
    onMicTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("composer_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Attach button
        IconButton(
            onClick = onAttachTap,
            enabled = isConnected,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attach file",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Model chip
        FilterChip(
            selected = currentSessionModel != null,
            onClick = onModelTap,
            label = {
                Text(
                    text = currentSessionModel ?: "Model",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.testTag("model_chip"),
        )

        // Reasoning chip
        FilterChip(
            selected = reasoningLevel != null,
            onClick = onReasoningTap,
            label = {
                Text(
                    text = buildReasoningLabel(reasoningLevel),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.testTag("reasoning_chip"),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Mic / Stop button
        IconButton(
            onClick = onMicTap,
            enabled = isConnected,
            colors =
                if (isListening) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
            modifier =
                Modifier
                    .size(36.dp)
                    .testTag(if (isListening) "mic_stop_button" else "mic_button"),
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop listening" else "Mic",
            )
        }
    }
}

/**
 * Build a human-readable label from a reasoning effort level.
 *
 * @param level "low", "medium", "high", or null for model default.
 * @return Display string such as "🧠 Low", "🧠 Med", "🧠 High", or "🧠 Auto".
 */
private fun buildReasoningLabel(level: String?): String {
    return when (level) {
        "low" -> "🧠 Low"
        "medium" -> "🧠 Med"
        "high" -> "🧠 High"
        else -> "🧠 Auto"
    }
}
