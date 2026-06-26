package com.m57.hermescontrol.ui.chat

/**
 * Parses slash commands and returns a [SlashResult] describing what action
 * the ViewModel should take.
 *
 * Pure logic — no I/O, no Android dependencies.
 *
 * Only commands that MUST be handled client-side (immediate UX) are here.
 * Everything else is forwarded to the backend via [SlashResult.RpcDispatch].
 */
class SlashCommandDispatcher {
    fun dispatch(command: String): SlashResult {
        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase()

        return when (cmd) {
            "/stop", "/interrupt" -> SlashResult.Interrupt
            "/new" -> SlashResult.NewSession
            else -> SlashResult.RpcDispatch
        }
    }
}

/**
 * The result of dispatching a slash command.
 */
sealed class SlashResult {
    /** Interrupt the active session (client-side immediate). */
    data object Interrupt : SlashResult()

    /** Create a new session (client-side immediate). */
    data object NewSession : SlashResult()

    /** Forward to command.dispatch via WebSocket. */
    data object RpcDispatch : SlashResult()
}
