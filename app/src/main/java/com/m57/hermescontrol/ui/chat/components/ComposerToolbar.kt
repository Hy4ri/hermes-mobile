package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Bottom toolbar row for the chat composer.
 *
 * Layout: [📎 attach] [model chip] [←spacer→] [🧠 reasoning] [🎙 mic]
 *
 * The reasoning chip opens a dropdown menu to pick a level (instead of cycling).
 * When [canDisableReasoning] is false the "None" level is disabled with a
 * "reasoning always on" hint (issue #946). Absent key (null) means no
 * restriction is known — full scale offered.
 * When [supportsReasoning] is false the model takes no reasoning parameter
 * and the chip is disabled.
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
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
    fastMode: Boolean = false,
    fastSupported: Boolean = false,
    isFastModeChanging: Boolean = false,
    onToggleFastMode: () -> Unit = {},
) {
    var showReasoningMenu by remember { mutableStateOf(false) }
    val reasoningDisabledForModel = supportsReasoning == false
    val canDisable = canDisableReasoning

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
                contentDescription = stringResource(R.string.chat_attach_file),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Model chip — takes available space, fixed height
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
                    .weight(1f)
                    .height(28.dp)
                    .testTag("model_chip"),
        )

        // Reasoning & Generation controls with dropdown menu (Option 3)
        Box {
            FilterChip(
                selected = reasoningLevel != null || fastMode,
                onClick = { showReasoningMenu = true },
                enabled = isConnected,
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (fastMode) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = stringResource(R.string.chat_fast_mode_label),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text =
                                if (reasoningDisabledForModel) {
                                    if (fastMode) stringResource(R.string.chat_fast_mode_label) else "No reasoning"
                                } else {
                                    val rLabel = buildReasoningLabel(reasoningLevel)
                                    if (fastMode) {
                                        "${stringResource(
                                            R.string.chat_fast_mode_label,
                                        )} · $rLabel"
                                    } else {
                                        rLabel
                                    }
                                },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                modifier =
                    Modifier
                        .height(28.dp)
                        .testTag("reasoning_chip"),
            )

            DropdownMenu(
                expanded = showReasoningMenu,
                onDismissRequest = { showReasoningMenu = false },
                modifier = Modifier.widthIn(min = 220.dp),
            ) {
                // ── Fast Mode Toggle Item ──
                val fastAvailable = isConnected && fastSupported
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint =
                                if (fastMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = stringResource(R.string.chat_fast_mode_label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (fastMode) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (!fastSupported) {
                                Text(
                                    text = stringResource(R.string.chat_fast_mode_unavailable),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (isFastModeChanging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Switch(
                                checked = fastMode,
                                onCheckedChange = null, // MenuItem click owns the trigger
                                enabled = fastAvailable && !isFastModeChanging,
                                modifier =
                                    Modifier
                                        .scale(0.85f)
                                        .testTag("fast_mode_switch"),
                            )
                        }
                    },
                    onClick = {
                        if (fastAvailable && !isFastModeChanging) {
                            onToggleFastMode()
                        }
                    },
                    enabled = fastAvailable && !isFastModeChanging,
                )

                HorizontalDivider()

                Text(
                    text = "REASONING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (canDisable == false) {
                    Text(
                        text = "reasoning always on",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (reasoningDisabledForModel) {
                    Text(
                        text = "no reasoning parameter for this model",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
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
                    val isNone = level == "none"
                    val noneDisabled = isNone && (canDisable == false || reasoningDisabledForModel)
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
                                    when {
                                        noneDisabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        reasoningLevel == level -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        },
                        onClick = {
                            showReasoningMenu = false
                            onReasoningSelected(level)
                        },
                        enabled = !noneDisabled && !reasoningDisabledForModel,
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
private fun buildReasoningLabel(level: String?): String =
    when (level) {
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
