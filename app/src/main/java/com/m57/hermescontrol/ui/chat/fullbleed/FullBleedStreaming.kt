package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.ui.chat.ChatMessage
import kotlinx.coroutines.delay

/**
 * Wraps a streaming [FullBleedAgentMessage] with a word-by-word typing reveal
 * effect (full-bleed counterpart to the removed bubble streaming wrapper).
 *
 * Shows words one at a time at [typingDelayMs] intervals while the message is
 * still streaming. When streaming completes the full text is shown
 * immediately. The underlying [ChatMessage.content] in state is never
 * modified — this is a display-only transformation.
 *
 * Deliberately a separate wrapper (not a genericized version of the bubble
 * one): the two renderers are parallel code paths by design (issue #866),
 * and the wrappers die together when the bubble path is removed.
 */
@Composable
internal fun StreamingFullBleedWithTypingEffect(
    streaming: ChatMessage,
    typingDelayMs: Int,
    isDark: Boolean,
    showTurnHeader: Boolean = true,
    showReasoning: Boolean = true,
    onAnimationComplete: () -> Unit = {},
) {
    var visibleWordCount by remember { mutableIntStateOf(0) }
    var caretVisible by remember { mutableStateOf(true) }
    val currentContent = rememberUpdatedState(streaming.content)
    val currentIsStreaming = rememberUpdatedState(streaming.isStreaming)
    val currentDelayMs = rememberUpdatedState(typingDelayMs)

    // Blinking typing caret while the message is still streaming.
    LaunchedEffect(Unit) {
        while (currentIsStreaming.value) {
            delay(400)
            caretVisible = !caretVisible
        }
        caretVisible = false
    }

    // Timer that ticks at the configured delay, incrementing the visible word
    // count each tick. Stops ticking when streaming ends, then shows all words.
    // Optimized: split is only called when waiting for new content, not per tick.
    LaunchedEffect(Unit) {
        var wordCount = 0
        while (true) {
            if (visibleWordCount < wordCount) {
                delay(currentDelayMs.value.toLong())
                visibleWordCount++
            } else {
                if (!currentIsStreaming.value) {
                    onAnimationComplete()
                    break
                }
                // Only split when we need to check for new content arriving
                val words = currentContent.value.split(" ")
                wordCount = words.size
                if (visibleWordCount < wordCount) continue
                delay(100)
            }
        }
        visibleWordCount = Int.MAX_VALUE
    }

    // Derive display text from the latest full content at each recomposition
    val words = streaming.content.split(" ")
    val visibleCount =
        if (visibleWordCount >= Int.MAX_VALUE / 2) {
            words.size
        } else {
            visibleWordCount.coerceIn(0, words.size)
        }
    val displayText =
        words.take(visibleCount.coerceAtLeast(1)).joinToString(" ") +
            if (caretVisible && currentIsStreaming.value) "▍" else ""

    FullBleedAgentMessage(
        message = streaming.copy(content = displayText),
        showTurnHeader = showTurnHeader,
        showReasoning = showReasoning,
        isDarkTheme = isDark,
        searchQuery = "",
        isCurrentMatch = false,
    )
}
