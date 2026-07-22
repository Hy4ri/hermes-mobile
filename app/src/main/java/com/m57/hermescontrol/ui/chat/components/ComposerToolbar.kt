package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom toolbar row for the chat composer.
 *
 * Layout: [📎 attach] [model chip] [←spacer→] [🧠 reasoning] [🎙 mic]
 *
 * The reasoning chip opens a dropdown menu to pick a level (instead of cycling).
 */
@Composable
fun ComposerToolbar(
    isConnected: Boolean,
    currentSessionModel: String?,
    reasoningLevel: String?,
    isListening: Boolean,
    onAttachTap: () -> Unit,
    onModelTap: () -> Unit,
    onReasoningSelected: (String?) -> Unit,
    onMicTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showReasoningMenu by remember { mutableStateOf(false) }

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

        // Model chip — fixed width, text scrolls if too long
        FilterChip(
            selected = currentSessionModel != null,
            onClick = onModelTap,
            label = {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = currentSessionModel ?: "Model",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            },
            modifier =
                Modifier
                    .widthIn(min = 140.dp)
                    .testTag("model_chip"),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Reasoning chip with dropdown menu (right side, next to mic)
        Box {
            FilterChip(
                selected = reasoningLevel != null,
                onClick = { showReasoningMenu = true },
                label = {
                    Text(
                        text = buildReasoningLabel(reasoningLevel),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp)
                        .testTag("reasoning_chip"),
            )

            DropdownMenu(
                expanded = showReasoningMenu,
                onDismissRequest = { showReasoningMenu = false },
            ) {
                val allLevels =
                    listOf(
                        "none" to "None",
                        "minimal" to "Minimal",
                        "low" to "Low",
                        "medium" to "Med",
                        "high" to "High",
                        "xhigh" to "XHigh",
                        "max" to "Max",
                        "ultra" to "Ultra",
                    )
                allLevels.forEach { (level, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                fontWeight =
                                    if (reasoningLevel == level) {
                                        MaterialTheme.typography.bodyMedium.fontWeight
                                    } else {
                                        null
                                    },
                                color =
                                    if (reasoningLevel == level) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        },
                        onClick = {
                            showReasoningMenu = false
                            onReasoningSelected(level)
                        },
                    )
                }
            }
        }

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
 * @param level One of: "none", "minimal", "low", "medium", "high",
 *              "xhigh", "max", "ultra", or null for model default.
 * @return Display string such as "None", "Low", "XHigh", "Ultra", etc.
 */
private fun buildReasoningLabel(level: String?): String {
    return when (level) {
        null -> "Med"
        "none" -> "None"
        "minimal" -> "Minimal"
        "low" -> "Low"
        "medium" -> "Med"
        "high" -> "High"
        "xhigh" -> "XHigh"
        "max" -> "Max"
        "ultra" -> "Ultra"
        else -> level
    }
}
