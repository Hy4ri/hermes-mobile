package com.m57.hermescontrol.ui.chat

/** Captured before asynchronous clipboard access; never retarget a late image to a new chat. */
internal data class ChatAttachmentTarget(
    // null: a new-chat draft (no session created yet).
    val sessionId: String?,
    val generation: Long,
    val baseUrl: String,
    val connectionProfileId: String?,
    val agentProfileId: String?,
)
