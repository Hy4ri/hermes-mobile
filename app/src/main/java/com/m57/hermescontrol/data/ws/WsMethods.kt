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
    const val SESSION_BRANCH = "session.branch"

    // ── Interaction ───────────────────────────────────────────────────────
    const val PROMPT_SUBMIT = "prompt.submit"
    const val CLARIFY_RESPOND = "clarify.respond"
    const val APPROVAL_RESPOND = "approval.respond"
    const val SUDO_RESPOND = "sudo.respond"
    const val SECRET_RESPOND = "secret.respond"

    // ── Commands catalog ──────────────────────────────────────────────────
    const val COMMANDS_CATALOG = "commands.catalog"
    const val COMMAND_DISPATCH = "command.dispatch"
    const val SLASH_EXEC = "slash.exec"
    const val CONFIG_SET = "config.set"

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

    // ── Billing / subscription (issue #628) ─────────────────────────────
    // Adopted from the backend release audit (hermes-agent 0bf44d557..614dc194e).
    // `credits.view` was REMOVED upstream; these replace it. `usage.bars`
    // surfaces the same usage data the legacy credits block rendered.

    /** Current subscription/plan state. Fail-open: logged-out → {logged_in:false}. */
    const val SUBSCRIPTION_STATE = "subscription.state"

    /** Chargeless effect quote for a target subscription type. */
    const val SUBSCRIPTION_PREVIEW = "subscription.preview"

    /** Schedule a downgrade or cancellation. */
    const val SUBSCRIPTION_CHANGE = "subscription.change"

    /** Resume a cancelled/paused subscription. */
    const val SUBSCRIPTION_RESUME = "subscription.resume"

    /** Upgrade — hits POST /api/billing/subscription/upgrade (prorate + charge). */
    const val SUBSCRIPTION_UPGRADE = "subscription.upgrade"

    /** Usage bars (token/cost breakdown the legacy credits block rendered). */
    const val USAGE_BARS = "usage.bars"
}
