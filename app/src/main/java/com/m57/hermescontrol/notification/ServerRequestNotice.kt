package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.ws.WsEvent

/** What a background request notification asks the user to do. */
internal enum class RequestNoticeKind {
    /** An approval the notification may answer with "once" or "deny". */
    APPROVAL,

    /** A question: the notification only deep-links into the chat. */
    CLARIFY,

    /** A secret, sudo, or vault prompt: never answered from the shade. */
    CREDENTIAL,
}

/**
 * One pending gateway request shown as a notification. Immutable: it pins the
 * profile scope, the runtime session, and the exact request identity so an
 * action can never be redirected to a newer request.
 */
internal data class RequestNotice(
    val kind: RequestNoticeKind,
    /** JSON-RPC id of a modern server request; null for the legacy event path. */
    val serverRequestId: String?,
    /** Backend `request_id` used by legacy `approval.respond`. */
    val legacyRequestId: String?,
    val sessionId: String?,
    val scopeId: String,
    val preview: String?,
    val canApproveOnce: Boolean,
    val canDeny: Boolean,
) {
    /** Stable identity: one notification and one dedupe slot per request. */
    val key: String
        get() = serverRequestId?.let { "srv:$scopeId:$it" } ?: "leg:$scopeId:${legacyRequestId.orEmpty()}"

    /** Without a request identity an answer could hit the wrong prompt. */
    val answerable: Boolean
        get() {
            val hasIdentity = serverRequestId != null || (legacyRequestId != null && !sessionId.isNullOrBlank())
            return kind == RequestNoticeKind.APPROVAL && (canApproveOnce || canDeny) && hasIdentity
        }
}

private val CREDENTIAL_METHODS = setOf("sudo", "secret", "vault.code", "vault.save_login", "vault.unlock_prompt")

private fun approvalChoices(choices: List<String>?): Pair<Boolean, Boolean> =
    // A missing list means the gateway did not restrict the choices.
    if (choices == null) true to true else ("once" in choices) to ("deny" in choices)

/** Classify a modern server request. Returns null for methods that need no alert. */
internal fun requestNoticeFor(
    request: WsEvent.ServerRequest,
    scopeId: String,
): RequestNotice? {
    val params = request.params
    val sessionId = params["session_id"] as? String
    return when (request.method) {
        "approval" -> {
            val (once, deny) = approvalChoices((params["choices"] as? List<*>)?.filterIsInstance<String>())
            RequestNotice(
                kind = RequestNoticeKind.APPROVAL,
                serverRequestId = request.id,
                legacyRequestId = params["request_id"] as? String,
                sessionId = sessionId,
                scopeId = scopeId,
                preview =
                    (params["description"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (params["command"] as? String)?.takeIf { it.isNotBlank() },
                canApproveOnce = once,
                canDeny = deny,
            )
        }

        "clarify" -> {
            RequestNotice(
                kind = RequestNoticeKind.CLARIFY,
                serverRequestId = request.id,
                legacyRequestId = null,
                sessionId = sessionId,
                scopeId = scopeId,
                preview = null,
                canApproveOnce = false,
                canDeny = false,
            )
        }

        in CREDENTIAL_METHODS -> {
            RequestNotice(
                kind = RequestNoticeKind.CREDENTIAL,
                serverRequestId = request.id,
                legacyRequestId = null,
                sessionId = sessionId,
                scopeId = scopeId,
                // Never echo a secret prompt text into the shade.
                preview = null,
                canApproveOnce = false,
                canDeny = false,
            )
        }

        else -> {
            null
        }
    }
}

/** Classify a legacy `approval.request` event. */
internal fun requestNoticeFor(
    event: WsEvent.ApprovalRequest,
    scopeId: String,
): RequestNotice {
    val (once, deny) = approvalChoices(event.choices)
    return RequestNotice(
        kind = RequestNoticeKind.APPROVAL,
        serverRequestId = event.serverRequestId,
        legacyRequestId = event.requestId,
        sessionId = event.sessionId,
        scopeId = scopeId,
        preview =
            event.description?.takeIf { it.isNotBlank() }
                ?: event.command?.takeIf { it.isNotBlank() },
        canApproveOnce = once,
        canDeny = deny,
    )
}

/**
 * Tracks open request notices. A request alerts once: replays (reconnect/resume
 * re-deliver open requests) and requests already answered or cancelled do not
 * alert again. Cancel removes only the notice with the same identity.
 */
internal class PendingRequestNotices(
    private val maxClosed: Int = 128,
) {
    private class Entry(
        val notice: RequestNotice,
        var generation: Int,
    )

    private val open = LinkedHashMap<String, Entry>()
    private val closed = LinkedHashSet<String>()
    private val closedServerIds = LinkedHashSet<String>()

    /** Returns true when the notice is new and should alert. */
    @Synchronized
    fun register(
        notice: RequestNotice,
        connectionGeneration: Int,
    ): Boolean {
        val key = notice.key
        val existing = open[key]
        if (existing != null) {
            // Replay on a new socket: same request, new transport generation.
            existing.generation = connectionGeneration
            return false
        }
        if (key in closed || notice.serverRequestId in closedServerIds) return false
        open[key] = Entry(notice, connectionGeneration)
        return true
    }

    @Synchronized
    fun find(key: String): Pair<RequestNotice, Int>? = open[key]?.let { it.notice to it.generation }

    /** Mark a request finished (answered or cancelled). Returns its notice if it was open. */
    @Synchronized
    fun close(key: String): RequestNotice? {
        val entry = open.remove(key)
        closed.add(key)
        while (closed.size > maxClosed) closed.remove(closed.first())
        return entry?.notice
    }

    /** Close the open modern request with this JSON-RPC id (cancel frame). */
    @Synchronized
    fun closeServerRequest(serverRequestId: String): RequestNotice? {
        // Remember the id even when no notice exists yet, so a cancel that beats
        // the delayed alert still suppresses it.
        closedServerIds.add(serverRequestId)
        while (closedServerIds.size > maxClosed) closedServerIds.remove(closedServerIds.first())
        val key = open.entries.firstOrNull { it.value.notice.serverRequestId == serverRequestId }?.key
        return key?.let { close(it) }
    }

    @Synchronized
    fun clearOpen(): List<RequestNotice> {
        val notices = open.values.map { it.notice }
        open.clear()
        return notices
    }
}
