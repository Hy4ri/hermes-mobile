package com.m57.hermescontrol.data.ws

/** JSON-RPC method name constants used with [HermesWsClient.send]. */
object WsMethods {
    // ── Session ───────────────────────────────────────────────────────────
    const val SESSION_LIST = "session.list"
    const val SESSION_ACTIVE_LIST = "session.active_list"
    const val SESSION_STATUS = "session.status"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_RESUME = "session.resume"
    const val SESSION_CREATE = "session.create"
    const val SESSION_INTERRUPT = "session.interrupt"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_TITLE = "session.title"

    // ── Interaction ───────────────────────────────────────────────────────
    const val PROMPT_SUBMIT = "prompt.submit"
    const val CLARIFY_RESPOND = "clarify.respond"
    const val APPROVAL_RESPOND = "approval.respond"

    // ── Commands catalog ──────────────────────────────────────────────────
    const val COMMANDS_CATALOG = "commands.catalog"
    const val COMMAND_DISPATCH = "command.dispatch"
    const val COMMAND_RESOLVE = "command.resolve"
}
