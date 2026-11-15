package com.m57.hermescontrol.ui.chat

/** Captured before asynchronous clipboard access; never retarget a late image to a new chat. */
internal data class ChatAttachmentTarget(
    // null for a new-chat draft, which has no session yet.
    val sessionId: String?,
    val generation: Long,
    val baseUrl: String,
    val connectionProfileId: String?,
    val agentProfileId: String?,
)
