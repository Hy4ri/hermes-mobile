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
    const val SESSION_REDIRECT = "session.redirect"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_TITLE = "session.title"
    const val SESSION_BRANCH = "session.branch"

    /** Live context-window occupancy for the chat meter (desktop-mirror). */
    const val SESSION_CONTEXT_BREAKDOWN = "session.context_breakdown"

    /** Lifetime usage snapshot (calls, token totals, compression count). */
    const val SESSION_USAGE = "session.usage"

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

    // ── Profile-scoped method set ────────────────────────────────────────
    // Methods that the TUI gateway resolves against the per-profile
    // HERMES_HOME / state.db when `params.profile` is present.
    //
    // Two categories on the server:
    //   (a) @_profile_scoped (method_ctx.py) — binds HERMES_HOME via
    //       ContextVar for the handler's lifetime.
    //   (b) Manual params.get("profile") / _profile_db(params) — opens
    //       the named profile's state.db explicitly.
    //
    // This set is the union of both.  Only methods the mobile app
    // actually calls are listed (pet.* are included for completeness
    // since they read per-profile config).  The set is intentionally an
    // explicit allowlist: unknown / new methods default to un-scoped,
    // which is safe — the server ignores an unrecognised `profile` key.

    /**
     * Methods whose server handler reads `params["profile"]` to resolve the
     * correct per-profile data.  Used by [WsProfileParams] to decide which
     * RPCs get the active profile injected automatically.
     */
    val PROFILE_SCOPED_METHODS: Set<String> =
        setOf(
            // Category (b): manual params.get("profile") / _profile_db
            // Evidence: methods_session.py lines 42, 164, 232, 317, 845
            SESSION_CREATE, // session.create
            SESSION_LIST, // session.list
            SESSION_RESUME, // session.resume
            SESSION_DELETE, // session.delete
            SESSION_STATUS, // session.status
            // session.most_recent uses _profile_db(params) too, but
            // mobile doesn't call it; documented for completeness.
            // Category (a): @_profile_scoped decorator
            // Evidence: methods_session.py @_profile_scoped at each @method
            // Binds HERMES_HOME so config/skills/pets resolve to the
            // focused profile.
            "verification.status", // line 282
            "pet.info", // line 1253
            "pet.info.meta", // line 1279
            "pet.cells", // line 1302
            "pet.gallery", // line 1407
            "pet.select", // line 1490
            "pet.remove", // line 1517
            "pet.export", // line 1547
            "pet.rename", // line 1574
            "pet.thumb", // line 1612
            "pet.disable", // line 1647
            "pet.scale", // line 1661
        )
}
