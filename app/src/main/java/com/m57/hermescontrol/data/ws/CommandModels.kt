package com.m57.hermescontrol.data.ws

/**
 * Response from `commands.catalog` RPC — full catalog of available
 * slash commands, subcommands, and alias mappings.
 */
data class CommandCatalog(
    val pairs: List<List<String>> = emptyList(),
    val sub: Map<String, List<String>> = emptyMap(),
    val canon: Map<String, String> = emptyMap(),
    val categories: List<CommandCategory> = emptyList(),
    val skillCount: Int = 0,
    val warning: String = "",
)

/**
 * A named category within the command catalog.
 */
data class CommandCategory(
    val name: String,
    val pairs: List<List<String>>,
)

/**
 * Response from `command.dispatch` — structured result of executing
 * a slash command on the backend.
 */
data class CommandDispatchResponse(
    val type: String,
    val message: String? = null,
    val output: String? = null,
    val name: String? = null,
    val target: String? = null,
)
