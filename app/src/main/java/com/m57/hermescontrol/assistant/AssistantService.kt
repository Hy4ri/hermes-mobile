package com.m57.hermescontrol.assistant

import android.service.voice.VoiceInteractionService

/**
 * Top-level digital-assistant service. The system keeps this running while
 * Hermes Mobile is the default assistant; the actual trigger handling lives
 * in [AssistantSessionService] / [AssistantSession].
 */
class AssistantService : VoiceInteractionService()
