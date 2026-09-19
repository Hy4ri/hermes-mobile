package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
 * Bottom controls row for the chat composer, rendered inside the composer card.
 *
 * Layout: [+ attach] [model · reasoning pill ──free space──] [mic] [action]
 *
 * All controls are flat and borderless; hierarchy comes from fill brightness.
 * The pill shows the model and the reasoning level side by side — tapping the
 * model half opens the model picker, tapping the level half opens the level menu.
 *
 * The trailing action button morphs: while a send is possible it sends,
 * otherwise it carries the mic action. The flat mic button only appears next
 * to it while it is in send mode, so dictation stays reachable at all times.
 *
 * The reasoning menu picks a level (instead of cycling).
 * When [canDisableReasoning] is false the "None" level is disabled with a
 * "reasoning always on" hint (issue #946). Absent key (null) means no
 * restriction is known — full scale offered.
 * When [supportsReasoning] is false the model takes no reasoning parameter
 * and the level menu is disabled.
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
    canSend: Boolean = false,
    showSend: Boolean = canSend,
    onSend: () -> Unit = {},
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
    fastMode: Boolean = false,
    fastSupported: Boolean = false,
    isFastModeChanging: Boolean = false,
    onToggleFastMode: () -> Unit = {},
    showModelProvider: Boolean = false,
) {
    var showReasoningMenu by remember { mutableStateOf(false) }
    val palette = composerPalette()
    val reasoningDisabledForModel = supportsReasoning == false
    val canDisable = canDisableReasoning

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 6.dp)
                .testTag("composer_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Attach button
        FilledIconButton(
            onClick = onAttachTap,
            enabled = isConnected,
            colors = flatIconButtonColors(palette),
            modifier = Modifier.size(ControlSize).testTag("attachment_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.chat_attach_file),
            )
        }

        // Model + reasoning pill — wraps its content inside the free space,
        // pushing the mic/action buttons to the far end
        val modelScrollState = rememberScrollState()
        val modelLabel =
            currentSessionModel?.let { model ->
                composerModelLabel(model, showProvider = showModelProvider)
            } ?: "Model"
        LaunchedEffect(modelLabel) {
            modelScrollState.scrollTo(0)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier =
                    Modifier
                        .width(IntrinsicSize.Max)
                        .height(ControlSize)
                        .clip(CircleShape)
                        .background(palette.control),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .horizontalScroll(modelScrollState)
                            .clickable(onClick = onModelTap)
                            .testTag("model_chip"),
                ) {
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onControl,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .wrapContentHeight()
                                .padding(start = 16.dp, end = 6.dp),
                    )
                }

                Box {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .clickable(enabled = isConnected) { showReasoningMenu = true }
                                .padding(start = 6.dp, end = 12.dp)
                                .testTag("reasoning_chip"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val levelColor =
                            palette.onControlVariant.copy(
                                alpha = if (isConnected) 1f else 0.38f,
                            )
                        if (fastMode) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = stringResource(R.string.chat_fast_mode_label),
                                tint = levelColor,
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = levelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Chevron marks the level half of the pill as a menu trigger
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.chat_reasoning_menu_desc),
                            tint = levelColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }

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
            }
        }

        // Flat mic / stop button — only while the action button is in send mode
        AnimatedVisibility(
            visible = showSend,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            FilledIconButton(
                onClick = onMicTap,
                enabled = isConnected,
                colors = if (isListening) listeningIconButtonColors() else flatIconButtonColors(palette),
                modifier =
                    Modifier
                        .size(ControlSize)
                        .testTag(if (isListening) "mic_stop_button" else "mic_button"),
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Outlined.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Mic",
                )
            }
        }

        // Action button — send when a send is possible, mic / stop otherwise
        FilledIconButton(
            onClick = if (showSend) onSend else onMicTap,
            enabled = if (showSend) canSend else isConnected,
            colors =
                if (!showSend && isListening) {
                    listeningIconButtonColors()
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = palette.action,
                        contentColor = palette.onAction,
                    )
                },
            modifier =
                Modifier
                    .size(ControlSize)
                    .testTag(
                        when {
                            showSend -> "send_button"
                            isListening -> "mic_stop_button"
                            else -> "mic_button"
                        },
                    ),
        ) {
            Crossfade(
                targetState =
                    when {
                        showSend -> ActionGlyph.SEND
                        isListening -> ActionGlyph.STOP
                        else -> ActionGlyph.VOICE
                    },
                label = "composer_action_glyph",
            ) { glyph ->
                when (glyph) {
                    ActionGlyph.SEND -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send_desc),
                        )
                    }

                    ActionGlyph.STOP -> {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop listening")
                    }

                    ActionGlyph.VOICE -> {
                        Icon(imageVector = Icons.Outlined.Mic, contentDescription = "Mic")
                    }
                }
            }
        }
    }
}

private val ControlSize = 36.dp

private enum class ActionGlyph { SEND, STOP, VOICE }

@Composable
private fun flatIconButtonColors(palette: ComposerPalette): IconButtonColors =
    IconButtonDefaults.filledIconButtonColors(
        containerColor = palette.control,
        contentColor = palette.onControl,
    )

@Composable
private fun listeningIconButtonColors(): IconButtonColors =
    IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    )

/**
 * Model label for the composer pill:
 * When [showProvider] is true, shows the session's "provider/model" id with
 * only the "custom:" marker removed from the provider ("custom:acme/glm-5.3"
 * → "acme/glm-5.3"). The provider is kept so the same model served by
 * different providers never renders identically.
 * When [showProvider] is false (default), shows only the model name ("openai/gpt-5"
 * → "gpt-5", "custom:acme/glm-5.3" → "glm-5.3").
 */
internal fun composerModelLabel(
    sessionModel: String,
    showProvider: Boolean = false,
): String {
    val slash = sessionModel.indexOf('/')
    if (slash <= 0) return sessionModel

    val rawProvider = sessionModel.substring(0, slash)
    val model = sessionModel.substring(slash + 1)
    if (model.isBlank()) return sessionModel

    if (rawProvider == CUSTOM_PROVIDER_PREFIX) return sessionModel

    if (!showProvider) {
        val leaf = sessionModel.substringAfterLast('/')
        return if (leaf.isNotBlank()) leaf else sessionModel
    }

    val provider =
        if (rawProvider.startsWith(CUSTOM_PROVIDER_PREFIX) && rawProvider.length > CUSTOM_PROVIDER_PREFIX.length) {
            rawProvider.removePrefix(CUSTOM_PROVIDER_PREFIX)
        } else {
            rawProvider
        }
    return "$provider/$model"
}

private const val CUSTOM_PROVIDER_PREFIX = "custom:"

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
