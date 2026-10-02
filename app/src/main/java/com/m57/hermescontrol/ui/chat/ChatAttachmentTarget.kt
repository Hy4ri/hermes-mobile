package com.m57.hermescontrol.ui.chat

/** Captured before asynchronous clipboard access; never retarget a late image to a new chat. */
internal data class ChatAttachmentTarget(
    val sessionId: String,
    val generation: Long,
    val baseUrl: String,
    val connectionProfileId: String?,
    val agentProfileId: String?,
)
