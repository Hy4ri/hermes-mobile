package com.m57.hermescontrol.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub recognition service. The framework requires a recognition service to
 * be declared in voice_interaction_service.xml for the assistant component to
 * be valid (AOSP VoiceInteractionServiceInfo parse error otherwise), but v1
 * has no speech input — long-press Home just opens the app.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(
        intent: Intent,
        callback: Callback,
    ) = Unit

    override fun onStopListening(callback: Callback) = Unit

    override fun onCancel(callback: Callback) = Unit
}
