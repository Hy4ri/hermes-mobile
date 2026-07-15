package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.serialization.Serializable

@Serializable
data class ServerStoreState(
    val host: String = "127.0.0.1",
    val port: Int = 9119,
    val autoReconnect: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColors: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val bottomNavDisplayMode: BottomNavDisplayMode = BottomNavDisplayMode.ICON_AND_TEXT,
    val bottomNavItems: List<String> =
        listOf("ChatScreen", "SkillsScreen", "CronJobsScreen", "SystemScreen", "SettingsScreen"),
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val pinnedModels: List<PinnedModel> = emptyList(),
    val wsAuthParam: String = "token",
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    // App display language. "system" = follow device locale; otherwise a BCP-47
    // language code such as "en" or "ko". Applied via ContextWrapper in MainActivity.
    val appLanguage: String = "system",
)
