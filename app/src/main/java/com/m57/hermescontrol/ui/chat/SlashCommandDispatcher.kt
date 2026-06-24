package com.m57.hermescontrol.ui.chat

/**
 * Parses slash commands and returns a [SlashResult] describing what action
 * the ViewModel should take.
 *
 * Pure logic — no I/O, no Android dependencies.
 */
class SlashCommandDispatcher {

    fun dispatch(command: String): SlashResult {
        val parts = command.split(" ")
        val cmd = parts[0].lowercase()

        return when (cmd) {
            "/stop", "/interrupt" -> SlashResult.Interrupt
            "/new" -> SlashResult.NewSession
            "/help" -> SlashResult.Message(HELP_TEXT)
            "/status" -> SlashResult.FetchStatus
            "/sessions" -> SlashResult.FetchSessions
            "/stats", "/system" -> SlashResult.FetchStats
            else -> SlashResult.Unknown(cmd)
        }
    }

    companion object {
        val HELP_TEXT = """
            **Available Commands:**
            • `/help` - Show this help menu
            • `/status` - Check gateway and platform status
            • `/sessions` - List all chat sessions
            • `/stats` or `/system` - Check system resource usage
            • `/new` - Create a new chat session
            • `/stop` or `/interrupt` - Interrupt the active run
        """.trimIndent()
    }
}

/**
 * The result of dispatching a slash command.
 */
sealed class SlashResult {
    /** Display a static message in the chat. */
    data class Message(val text: String) : SlashResult()

    /** Interrupt the active session. */
    data object Interrupt : SlashResult()

    /** Create a new session. */
    data object NewSession : SlashResult()

    /** Fetch gateway status via REST and display. */
    data object FetchStatus : SlashResult()

    /** Fetch session list via REST and display. */
    data object FetchSessions : SlashResult()

    /** Fetch system stats via REST and display. */
    data object FetchStats : SlashResult()

    /** Command not recognised. */
    data class Unknown(val cmd: String) : SlashResult()
}
