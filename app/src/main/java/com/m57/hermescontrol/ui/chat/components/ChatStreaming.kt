package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import kotlinx.coroutines.delay

/**
 * Wraps a streaming [ChatBubble] with a word-by-word typing reveal effect.
 * Shows words one at a time at [typingDelayMs] intervals while the message is
 * still streaming. When streaming completes the full text is shown immediately.
 * The underlying [ChatMessage.content] in state is never modified — this is a
 * display-only transformation.
 */
@Composable
fun StreamingBubbleWithTypingEffect(
    streaming: ChatMessage,
    typingDelayMs: Int,
    isDark: Boolean,
    onAnimationComplete: () -> Unit = {},
) {
    var visibleWordCount by remember { mutableIntStateOf(0) }
    val currentContent = rememberUpdatedState(streaming.content)
    val currentIsStreaming = rememberUpdatedState(streaming.isStreaming)
    val currentDelayMs = rememberUpdatedState(typingDelayMs)

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
                delay(100) // was 10 — reduced from 100Hz to 10Hz
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
    val displayText = words.take(visibleCount.coerceAtLeast(1)).joinToString(" ")

    ChatBubble(
        message = streaming.copy(content = displayText),
        isDarkTheme = isDark,
        searchQuery = "",
        isCurrentMatch = false,
    )
}
