package com.m57.hermescontrol.data.ws

import kotlinx.serialization.Serializable

/**
 * Response from `commands.catalog` RPC — full catalog of available
 * slash commands, subcommands, and alias mappings.
 */
@Serializable
data class CommandCatalog(
    val pairs: List<List<String>> = emptyList(),
    val sub: Map<String, List<String>> = emptyMap(),
    val canon: Map<String, String> = emptyMap(),
    val categories: List<CommandCategory> = emptyList(),
    val skillCount: Double = 0.0,
    val warning: String = "",
)

/**
 * A named category within the command catalog.
 */
@Serializable
data class CommandCategory(
    val name: String,
    val pairs: List<List<String>>,
)

/**
 * Desktop/CLI-only and TUI-only slash commands that do NOT function on mobile
 * (issue #574). Source of truth for the backend `cli_only` / TUI-only flags
 * returned by `commands.catalog`.
 *
 * Two consumers:
 * 1. The slash-command suggestion menu hides these (so users don't discover
 *    dead commands).
 * 2. [com.m57.hermescontrol.ui.chat.ChatViewModel.handleSlashCommand] blocks
 *    them at dispatch time (issue #576, deliverable #3) — a user who types one
 *    anyway gets a clear "not supported on mobile" message instead of a doomed
 *    RPC that produces TUI-only output or a confusing error.
 */
object CommandBlocklist {
    val UNSUPPORTED: Set<String> =
        setOf(
            "/clear",
            "/redraw",
            "/history",
            "/save",
            "/prompt",
            "/snapshot",
            "/handoff",
            "/journey",
            "/config",
            "/statusbar",
            "/timestamps",
            "/verbose",
            "/skin",
            "/indicator",
            "/busy",
            "/tools",
            "/toolsets",
            "/skills",
            "/pet",
            "/hatch",
            "/cron",
            "/reload",
            "/browser",
            "/plugins",
            "/billing",
            "/platforms",
            "/copy",
            "/paste",
            "/image",
            "/quit",
            // TUI-only extras (meaningless outside the TUI)
            "/compact",
            "/logs",
            "/mouse",
        )

    /**
     * True if [command] (e.g. "/clear" or "/CLEAR arg") is blocked on mobile.
     * Matches on the exact command name (the token before the first space),
     * so "/reload" is blocked while "/reload-mcp" (a supported command) is not.
     */
    fun contains(command: String): Boolean {
        val name = command.split(" ", limit = 2)[0].lowercase()
        return name in UNSUPPORTED
    }
}
