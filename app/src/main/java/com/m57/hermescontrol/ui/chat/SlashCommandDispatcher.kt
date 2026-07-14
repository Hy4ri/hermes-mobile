package com.m57.hermescontrol.ui.chat

/**
 * Parses slash commands and returns a [SlashResult] describing what action
 * the ViewModel should take.
 *
 * Pure logic — no I/O, no Android dependencies.
 *
 * Only commands that MUST be handled client-side (immediate UX) are here.
 * `/fork` and `/model` are NOT sent via [SlashResult.RpcDispatch] (which maps
 * to the `command.dispatch` RPC — that RPC only knows quick/plugin/bundle/skill
 * commands and 4018s on everything else). They are real backend slash commands
 * that must travel as normal prompt messages, so they get their own results.
 * Everything else is forwarded to the backend via [SlashResult.RpcDispatch].
 */
class SlashCommandDispatcher {
    fun dispatch(command: String): SlashResult {
        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase()

        return when (cmd) {
            "/stop", "/interrupt" -> SlashResult.Interrupt
            "/new" -> SlashResult.NewSession
            "/fork", "/branch" -> SlashResult.SessionBranch
            "/model" -> SlashResult.ModelSwitch
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

    /** Fork the active conversation via the session.branch WebSocket RPC. */
    data object SessionBranch : SlashResult()

    /**
     * Hot-swap the current session's model via the backend `/model` slash
     * command. Sent as a NORMAL prompt message (not command.dispatch), because
     * `command.dispatch` only knows quick/plugin/bundle/skill commands and
     * returns `4018: not a quick/plugin/bundle/skill command: model` for `/model`.
     */
    data object ModelSwitch : SlashResult()
}
