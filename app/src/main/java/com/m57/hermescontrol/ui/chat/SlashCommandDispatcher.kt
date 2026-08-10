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
 * a normal prompt makes the LLM treat it as text). `/update` is intercepted
 * too: the backend handler is interactive + session-exiting (confirmation modal
 * + relaunch as `hermes update`), so it can never produce the single response
 * the slash worker waits for and always dies with a 45s "slash worker timed
 * out" (issue #862). Everything else is forwarded to the backend via
 * [SlashResult.RpcDispatch].
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
            "/update" -> SlashResult.Update
            "/resume", "/history" -> SlashResult.OpenHistory
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

    /**
     * Trigger the backend update via the REST action API
     * (`POST /api/hermes/update`, the System screen's flow) and track it in
     * the shared [com.m57.hermescontrol.ui.common.ActionProgressDialog].
     * NOT sent via slash.exec — the backend `/update` handler is interactive +
     * session-exiting (confirmation modal + relaunch), so it can never produce
     * the single WS response the slash worker waits for and always times out
     * after 45s (issue #862).
     */
    data object Update : SlashResult()

    /**
     * Open the session history tab client-side (issue #864). /resume and
     * /history are CLI-flavored interactive commands the mobile gateway path
     * can't answer usefully — same class of problem as /update (#862). The
     * user picks a past session from history and resumes it from there.
     */
    data object OpenHistory : SlashResult()
}
