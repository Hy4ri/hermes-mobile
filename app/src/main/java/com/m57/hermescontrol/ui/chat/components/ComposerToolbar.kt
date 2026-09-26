package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.AddToQueue
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import kotlinx.coroutines.withTimeoutOrNull

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
@OptIn(ExperimentalFoundationApi::class)
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
    showQueue: Boolean = false,
    onSend: () -> Unit = {},
    onQueue: () -> Unit = {},
    onStopAndSend: () -> Unit = {},
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
    fastMode: Boolean = false,
    fastSupported: Boolean = false,
    isFastModeChanging: Boolean = false,
    onToggleFastMode: () -> Unit = {},
    showModelProvider: Boolean = false,
    reasoningWireLevel: String? = null,
    pendingReasoningLevel: String? = null,
    isSessionReady: Boolean = true,
    canInterrupt: Boolean = false,
    onMicHoldStart: () -> Unit = {},
    onMicHoldEnd: () -> Unit = {},
    onMicHoldCancel: () -> Unit = {},
    onMicLock: () -> Unit = {},
    isVoiceNoteLocked: Boolean = false,
    onSlideProgress: (Float) -> Unit = {},
    onStopGeneration: () -> Unit = {},
) {
    var showReasoningMenu by remember { mutableStateOf(false) }
    val palette = composerPalette()
    val reasoningDisabledForModel = supportsReasoning == false
    val canDisable = canDisableReasoning

    // Telegram-style voice notes: hold to record, release to send, slide left
    // to cancel, slide up to lock hands-free. Both mic controls run the same
    // single pointer loop (micHoldHandler), which owns the whole press until
    // finger-up — so the release that submits the note can never be lost
    // between recompositions.
    val currentIsConnected = rememberUpdatedState(isConnected)
    val currentShowSend = rememberUpdatedState(showSend)
    val currentCanInterrupt = rememberUpdatedState(canInterrupt)
    val currentIsVoiceNoteLocked = rememberUpdatedState(isVoiceNoteLocked)
    val currentOnMicHoldStart = rememberUpdatedState(onMicHoldStart)
    val currentOnMicHoldEnd = rememberUpdatedState(onMicHoldEnd)
    val currentOnMicHoldCancel = rememberUpdatedState(onMicHoldCancel)
    val currentOnMicLock = rememberUpdatedState(onMicLock)
    val currentOnSlideProgress = rememberUpdatedState(onSlideProgress)
    // The mic button is under the thumb during the gesture, so the outcome —
    // lock or cancel — must be felt as well as seen.
    val haptic = LocalHapticFeedback.current
    // Telegram's slide-to-cancel span: a third of the composer width, capped
    // at [MAX_CANCEL_DISTANCE_DP]. The gesture modifier sits on the mic button
    // itself (only ~40 dp wide), so the span derives from the screen width.
    val cancelDistancePx =
        with(LocalDensity.current) {
            minOf(
                LocalConfiguration.current.screenWidthDp.dp
                    .toPx() * 0.35f,
                MAX_CANCEL_DISTANCE_DP.dp.toPx(),
            )
        }
    val flatMicGesture =
        Modifier.pointerInput(Unit) {
            micHoldHandler(
                isEnabled = {
                    currentIsConnected.value && currentShowSend.value && !currentCanInterrupt.value &&
                        !currentIsVoiceNoteLocked.value
                },
                onHoldStart = { currentOnMicHoldStart.value() },
                onHoldEnd = { currentOnMicHoldEnd.value() },
                onHoldCancel = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnMicHoldCancel.value()
                },
                onHoldLock = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnMicLock.value()
                },
                onSlideProgress = { currentOnSlideProgress.value(it) },
                cancelDistance = cancelDistancePx,
            )
        }
    val actionMicGesture =
        Modifier.pointerInput(Unit) {
            micHoldHandler(
                isEnabled = {
                    currentIsConnected.value && !currentShowSend.value && !currentCanInterrupt.value &&
                        !currentIsVoiceNoteLocked.value
                },
                onHoldStart = { currentOnMicHoldStart.value() },
                onHoldEnd = { currentOnMicHoldEnd.value() },
                onHoldCancel = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnMicHoldCancel.value()
                },
                onHoldLock = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnMicLock.value()
                },
                onSlideProgress = { currentOnSlideProgress.value(it) },
                cancelDistance = cancelDistancePx,
            )
        }

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
                    val reasoningInteractive = isConnected && isSessionReady && pendingReasoningLevel == null
                    Row(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .clickable(enabled = reasoningInteractive) { showReasoningMenu = true }
                                .padding(start = 6.dp, end = 12.dp)
                                .testTag("reasoning_chip"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val levelColor =
                            palette.onControlVariant.copy(
                                alpha = if (reasoningInteractive) 1f else 0.38f,
                            )
                        if (fastMode) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = stringResource(R.string.chat_fast_mode_label),
                                tint = levelColor,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        if (pendingReasoningLevel != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text =
                                if (reasoningDisabledForModel) {
                                    if (fastMode) stringResource(R.string.chat_fast_mode_label) else "No reasoning"
                                } else {
                                    val targetEffort = pendingReasoningLevel ?: reasoningLevel
                                    val wire = if (pendingReasoningLevel != null) null else reasoningWireLevel
                                    val rLabel =
                                        buildReasoningLabel(
                                            level = targetEffort,
                                            wireLevel = wire,
                                            defaultLabel = stringResource(R.string.chat_reasoning_default),
                                        )
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
                            val isSelected = (pendingReasoningLevel ?: reasoningLevel) == level
                            val isClampedTarget =
                                isSelected && reasoningWireLevel != null &&
                                    !reasoningWireLevel.equals(level, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = label,
                                            fontWeight =
                                                if (isSelected) {
                                                    MaterialTheme.typography.bodyMedium.fontWeight
                                                } else {
                                                    null
                                                },
                                            color =
                                                when {
                                                    noneDisabled -> {
                                                        MaterialTheme.colorScheme.onSurface.copy(
                                                            alpha = 0.38f,
                                                        )
                                                    }

                                                    isSelected -> {
                                                        MaterialTheme.colorScheme.primary
                                                    }

                                                    else -> {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                },
                                        )
                                        if (isClampedTarget) {
                                            Text(
                                                text =
                                                    stringResource(
                                                        R.string.chat_reasoning_clamped_desc,
                                                        label,
                                                        buildReasoningLabel(reasoningWireLevel, defaultLabel = ""),
                                                    ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    showReasoningMenu = false
                                    onReasoningSelected(level)
                                },
                                enabled = !noneDisabled && !reasoningDisabledForModel && pendingReasoningLevel == null,
                            )
                        }
                    }
                }
            }
        }

        // Flat slot — mic while idle; while a generation can be interrupted it
        // keeps queue-send available (the action button carries Stop). During
        // session preparation the mic stays here and the action button is a
        // disabled send: Stop must not appear when there is nothing to
        // interrupt (review, PR #1250).
        AnimatedVisibility(
            visible = showSend,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            if (canInterrupt) {
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = flatIconButtonColors(palette),
                    modifier =
                        Modifier
                            .size(ControlSize)
                            .testTag("send_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send_desc),
                    )
                }
            } else {
                // Plain Box, not FilledIconButton: the hold gesture must sit
                // innermost in the pointer-modifier chain so it consumes the
                // release before the tap handler — impossible while the button
                // supplies its own clickable (review, PR #1280). Same
                // arrangement as the action button below.
                val micColors = if (isListening) listeningIconButtonColors() else flatIconButtonColors(palette)
                Box(
                    modifier =
                        Modifier
                            .size(ControlSize)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) micColors.containerColor else micColors.disabledContainerColor,
                            ).combinedClickable(
                                enabled = isConnected,
                                onClick = onMicTap,
                            ).testTag(if (isListening) "mic_stop_button" else "mic_button")
                            .then(flatMicGesture),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Outlined.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Mic",
                        tint = if (isConnected) micColors.contentColor else micColors.disabledContentColor,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            FilledIconButton(
                onClick = onQueue,
                enabled = canSend,
                colors = flatIconButtonColors(palette),
                modifier = Modifier.size(48.dp).testTag("queue_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.AddToQueue,
                    contentDescription = stringResource(R.string.chat_busy_queue_action),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Action button — send when a send is possible, mic / stop otherwise
        val stopAndSendLabel = stringResource(R.string.chat_busy_stop_and_send)
        // While a recording is locked this button finishes the voice note, so
        // it must stay enabled even when the draft alone would not send
        // (review, PR #1280).
        val actionEnabled =
            if (isVoiceNoteLocked) {
                true
            } else if (showSend) {
                canSend
            } else {
                isConnected
            }
        Box(
            modifier =
                Modifier
                    .size(if (showSend) 48.dp else ControlSize)
                    .clip(CircleShape)
                    .background(
                        if (!showSend && isListening) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            palette.action
                        },
                    ).semantics {
                        if (showSend && showQueue && canSend && !isVoiceNoteLocked) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(stopAndSendLabel) {
                                        onStopAndSend()
                                        true
                                    },
                                )
                        }
                    }.combinedClickable(
                        enabled = actionEnabled,
                        onClick = {
                            when {
                                // A locked recording always finishes with this
                                // button, draft or agent state regardless
                                // (review, PR #1280).
                                isVoiceNoteLocked -> onMicTap()

                                canInterrupt -> onStopGeneration()

                                showSend -> onSend()

                                else -> onMicTap()
                            }
                        },
                        onLongClick = if (showSend && showQueue && !isVoiceNoteLocked) onStopAndSend else null,
                        onLongClickLabel = if (showSend && showQueue && !isVoiceNoteLocked) stopAndSendLabel else null,
                    ).testTag(
                        when {
                            isVoiceNoteLocked -> "voice_note_send_button"
                            canInterrupt -> "stop_button"
                            showSend -> "send_button"
                            isListening -> "mic_stop_button"
                            else -> "mic_button"
                        },
                    ).then(if (showSend || canInterrupt) Modifier else actionMicGesture),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState =
                    when {
                        isVoiceNoteLocked -> ActionGlyph.SEND
                        canInterrupt -> ActionGlyph.STOP
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
                            contentDescription =
                                if (isVoiceNoteLocked) {
                                    stringResource(R.string.chat_voice_send_desc)
                                } else {
                                    stringResource(R.string.chat_send_desc)
                                },
                            tint = palette.onAction,
                        )
                    }

                    ActionGlyph.STOP -> {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription =
                                if (canInterrupt) {
                                    stringResource(R.string.chat_voice_stop_generating)
                                } else {
                                    "Stop listening"
                                },
                            tint =
                                if (isListening && !canInterrupt) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    palette.onAction
                                },
                        )
                    }

                    ActionGlyph.VOICE -> {
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = "Mic",
                            tint = palette.onAction,
                        )
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
fun buildReasoningLabel(
    level: String?,
    wireLevel: String? = null,
    defaultLabel: String = "Default",
): String {
    val reqLabel =
        when (level) {
            null -> defaultLabel
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
    if (wireLevel != null && !wireLevel.equals(level, ignoreCase = true) && level != "none") {
        val wireLabel =
            when (wireLevel) {
                "minimal" -> "Minimal"
                "low" -> "Low"
                "medium" -> "Med"
                "high" -> "High"
                "xhigh" -> "XHigh"
                "max" -> "Max"
                "ultra" -> "Ultra"
                else -> wireLevel
            }
        return "$reqLabel→$wireLabel"
    }
    return reqLabel
}

/**
 * Telegram-style mic gesture: press and hold past [HOLD_TO_RECORD_THRESHOLD_MS]
 * records a voice note, release sends it, dragging into the cancel zone
 * cancels, and sliding up past [MIC_SLIDE_LOCK_DP] locks the recording
 * hands-free. Short taps use the control's normal click handler for dictation,
 * so touch and accessibility actions share one callback path.
 *
 * Distances and outcomes mirror Telegram's recorder: the full cancel distance
 * is a third of the composer capped at [MAX_CANCEL_DISTANCE_DP]; releasing
 * below [CANCEL_RELEASE_ALPHA] of it cancels (dragging back out before release
 * un-cancels); dragging all the way cancels mid-gesture; [onSlideProgress]
 * reports the 1 → 0 drag fraction so the recording strip can track and fade
 * its hint; locking only arms while the gesture stays clear of the cancel zone
 * ([LOCK_MIN_ALPHA]).
 *
 * The whole down-to-up sequence lives in this one pointer loop, so the release
 * that submits can never be lost to a recomposition between press and
 * finger-up — an earlier interaction-source based approach could lose it and
 * leave the recorder running until some later tap "sent" the stale clip.
 *
 * Once the hold phase arms, every event is consumed: the shared click handler
 * otherwise also observes the stationary release that ends a hold (there is no
 * drag to cancel it), and re-dispatches the tap action — the system dictation
 * intent launched every time a voice note was sent (regression, #1247).
 */
private suspend fun PointerInputScope.micHoldHandler(
    isEnabled: () -> Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onHoldLock: () -> Unit,
    onSlideProgress: (Float) -> Unit,
    cancelDistance: Float,
) {
    val lockSlop = MIC_SLIDE_LOCK_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!isEnabled()) {
            return@awaitEachGesture
        }

        fun slideAlpha(position: Offset): Float =
            (1f + (position.x - down.position.x) / cancelDistance).coerceIn(0f, 1f)

        fun slidUp(position: Offset): Boolean = position.y - down.position.y < -lockSlop

        // Phase 1 — a finger-up before the threshold is a plain tap, left to
        // the click handler. Dragging into the cancel zone aborts the gesture.
        // Events are not consumed yet, so the tap path stays intact.
        var earlyUp = false
        var gestureAborted = false
        withTimeoutOrNull(HOLD_TO_RECORD_THRESHOLD_MS) {
            while (true) {
                val event = awaitPointerEvent()
                val change =
                    event.changes.firstOrNull { it.id == down.id }
                        ?: run {
                            gestureAborted = true
                            return@withTimeoutOrNull
                        }
                if (!change.pressed) {
                    earlyUp = true
                    return@withTimeoutOrNull
                }
                if (change.isConsumed || slideAlpha(change.position) < CANCEL_RELEASE_ALPHA) {
                    gestureAborted = true
                    return@withTimeoutOrNull
                }
            }
        }
        if (gestureAborted) {
            return@awaitEachGesture
        }
        if (earlyUp) {
            return@awaitEachGesture
        }

        // Phase 2 — the threshold passed with the finger down: record until
        // the finger lifts (send, unless dragged into the cancel zone), drags
        // all the way left (cancel), or slides up (lock hands-free).
        onHoldStart()
        var send = false
        var lock = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val alpha = slideAlpha(change.position)
                onSlideProgress(alpha)
                if (!change.pressed) {
                    change.consume()
                    send = alpha >= CANCEL_RELEASE_ALPHA
                    break
                }
                if (change.isConsumed) {
                    change.consume()
                    break
                }
                if (alpha <= 0f) {
                    change.consume()
                    break
                }
                if (slidUp(change.position) && alpha >= LOCK_MIN_ALPHA) {
                    lock = true
                    change.consume()
                    break
                }
                change.consume()
            }
        } finally {
            onSlideProgress(1f)
            when {
                lock -> onHoldLock()
                send -> onHoldEnd()
                else -> onHoldCancel()
            }
        }
    }
}

/** A press shorter than this is a tap; longer arms voice-note recording. */
private const val HOLD_TO_RECORD_THRESHOLD_MS = 400L

/** Sliding this far up from the mic button locks the recording hands-free. */
private const val MIC_SLIDE_LOCK_DP = 57

/** Full slide-to-cancel distance: a third of the composer (Telegram). */
private const val MAX_CANCEL_DISTANCE_DP = 140

/** Releasing below this drag fraction of the cancel distance cancels. */
private const val CANCEL_RELEASE_ALPHA = 0.45f

/** Locking only arms while the gesture stays clear of the cancel zone. */
private const val LOCK_MIN_ALPHA = 0.7f
