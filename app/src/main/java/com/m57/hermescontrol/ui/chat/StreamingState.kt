package com.m57.hermescontrol.ui.chat

data class StreamingState(
    val streamingMessage: ChatMessage? = null,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
)
