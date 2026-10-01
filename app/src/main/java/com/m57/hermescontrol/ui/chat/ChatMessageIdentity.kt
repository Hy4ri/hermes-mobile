package com.m57.hermescontrol.ui.chat

/**
 * Known `display_kind` wire values (#1336). [ChatMessage.displayKind] stays a raw
 * string because the backend can add kinds; these names keep the ones the app
 * interprets in one place.
 */
internal object DisplayKind {
    const val STEER = "steer"
    const val CLARIFY_RESPONSE = "clarify_response"
    const val LOCAL_FEEDBACK = "local_feedback"
    const val MODEL_SWITCH = "model_switch"
    const val PERSONALITY_SWITCH = "personality_switch"
    const val AUTO_CONTINUE = "auto_continue"
    const val ASYNC_DELEGATION_COMPLETE = "async_delegation_complete"
    const val SKILL_INVOCATION = "skill_invocation"
    const val INTERNAL_NOTIFICATION = "internal_notification"
    const val MAX_ITERATIONS_REACHED = "max_iterations_reached"

    /** Kinds that tag real user or local rows rather than timeline markers. */
    val nonMarkerKinds = setOf(STEER, CLARIFY_RESPONSE, LOCAL_FEEDBACK)
}

/**
 * The single owner of the REST transcript row-id format (#1336):
 * `rest-<sessionId>-<rowKey>`, where rowKey is the server row id under newest-anchored
 * paging or an absolute position under legacy paging.
 */
internal object RestMessageId {
    private const val PREFIX = "rest-"

    fun of(
        sessionId: String,
        rowKey: Any,
    ): String = "$PREFIX$sessionId-$rowKey"

    fun sessionPrefix(sessionId: String): String = "$PREFIX$sessionId-"

    fun isRest(id: String): Boolean = id.startsWith(PREFIX)
}
