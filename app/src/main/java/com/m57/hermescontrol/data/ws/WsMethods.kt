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
    const val SUDO_RESPOND = "sudo.respond"
    const val SECRET_RESPOND = "secret.respond"

    // ── Commands catalog ──────────────────────────────────────────────────
    const val COMMANDS_CATALOG = "commands.catalog"
    const val COMMAND_DISPATCH = "command.dispatch"
    const val COMMAND_RESOLVE = "command.resolve"

    // ── Attachments ───────────────────────────────────────────────────────

    /** Upload image bytes (base64) from a remote client. */
    const val IMAGE_ATTACH_BYTES = "image.attach_bytes"

    /** Stage a non-image file (data URL) for agent access. */
    const val FILE_ATTACH = "file.attach"

    // ── Background process manager (issue #532) ──────────────────────────

    /** List background processes owned by the active session. */
    const val PROCESS_LIST = "process.list"

    /** Kill a single background process (scoped to the active session). */
    const val PROCESS_KILL = "process.kill"

    // ── Composer path autocomplete (issue #536) ─────────────────────────

    /**
     * Resolve `@`-tagged path completions (e.g. `@file:`, `@folder:`, `@diff`)
     * against the gateway's session working directory. Mirrors the desktop
     * composer (`apps/desktop/.../use-context-suggestions.ts`).
     */
    const val COMPLETE_PATH = "complete.path"
}
