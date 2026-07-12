package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceDefinition(
    val id: String,
    val name: String,
    val accentArgb: Long = 0xFF8B7CFF,
    val sessionIds: List<String> = emptyList(),
)

@Serializable
data class SessionPreference(
    val sessionId: String,
    val pinned: Boolean = false,
    val modelAlias: String? = null,
    val queuedPromptText: String? = null,
)

@Serializable
data class ServerStoreState(
    val host: String = "100.101.12.16",
    val port: Int = 9119,
    val autoReconnect: Boolean = true,
    val screenCaptureProtectionEnabled: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColors: Boolean = false,
    val themePreset: ThemePreset = ThemePreset.CASSY,
    val bottomNavDisplayMode: BottomNavDisplayMode = BottomNavDisplayMode.ICON_AND_TEXT,
    val bottomNavItems: List<String> =
        listOf("ChatScreen", "SkillsScreen", "CronJobsScreen", "SystemScreen", "SettingsScreen"),
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val pinnedModels: List<PinnedModel> = emptyList(),
    val wsAuthParam: String = "token",
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    val workspaces: List<WorkspaceDefinition> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val openSessionIds: List<String> = emptyList(),
    val activeSessionId: String? = null,
    val sessionPreferences: List<SessionPreference> = emptyList(),
    val yoloWarningDismissed: Boolean = false,
)
