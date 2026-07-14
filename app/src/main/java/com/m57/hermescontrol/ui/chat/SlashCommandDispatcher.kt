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
 * commands and 4018s on everything else). They are real backend commands that
 * get their own results: `/fork` goes via the `session.branch` RPC and `/model`
 * via the `config.set` RPC (key="model" → gateway `_apply_model_switch`; the
 * TUI gateway's `prompt.submit` does NOT parse slash commands, so sending it as
 * a normal prompt makes the LLM treat it as text). Everything else is forwarded
 * to the backend via [SlashResult.RpcDispatch].
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
     * Hot-swap the current session's model via the backend `config.set` RPC
     * (key="model" → `_apply_model_switch`). NOT command.dispatch (4018s on
     * /model) and NOT prompt.submit (LLM would treat it as text). The value
     * string carries the flags `parse_model_flags` understands:
     * `/model <model> --provider <slug> --session`.
     */
    data object ModelSwitch : SlashResult()
}
