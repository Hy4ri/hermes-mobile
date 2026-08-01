package com.m57.hermescontrol.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/**
 * Helpers for reading and changing the Android digital-assistant role
 * (the slot Gemini occupies by default).
 */
object AssistantRole {
    /**
     * Whether this app currently holds the digital-assistant role, i.e. the
     * system will route long-press Home / corner-swipe to Hermes Mobile.
     */
    fun isDefaultAssistant(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, AssistantService::class.java),
            )
        }

    /**
     * Whether the device supports a digital assistant at all. On devices
     * without voice interaction support the role cannot be requested.
     * Below API 29 there is no availability query in the SDK, so assume
     * supported and let the fallback settings screen handle the rest.
     */
    fun isSupported(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        } else {
            true
        }

    /**
     * Intent that lets the user switch the default assistant to this app.
     * API 29+ shows the system role-request dialog; older versions fall back
     * to the voice-input settings screen where the assistant can be picked.
     * If the role request can't be built (role unavailable on some OEM/AOSP
     * builds), falls back to the generic voice-input settings screen.
     */
    fun requestRoleIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            runCatching {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }.getOrElse {
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            }
        } else {
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        }
}
