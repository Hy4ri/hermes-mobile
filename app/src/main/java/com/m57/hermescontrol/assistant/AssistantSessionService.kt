package com.m57.hermescontrol.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Spawns an [AssistantSession] each time the user invokes the assistant
 * (long-press Home / corner swipe).
 */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}
