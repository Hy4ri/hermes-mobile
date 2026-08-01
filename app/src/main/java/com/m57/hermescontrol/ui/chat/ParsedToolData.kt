package com.m57.hermescontrol.ui.chat

/**
 * Cleanly parsed representation of a tool call, extracted from the
 * tool.complete JSON payload (which contains both args and result).
 */
data class ParsedToolData(
    val toolName: String = "",
    val isTerminal: Boolean = false,
    val stdout: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val summaryText: String? = null,
    val durationSec: Double? = null,
    val mainOutput: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
    val isRunning: Boolean = false,
    val diffOutput: String? = null,
    val diffPath: String? = null,
)
