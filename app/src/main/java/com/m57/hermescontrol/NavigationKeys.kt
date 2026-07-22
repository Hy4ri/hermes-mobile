package com.m57.hermescontrol

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object LandingScreen : NavKey

@Serializable data object AuthLoginScreen : NavKey

@Serializable data object ChatScreen : NavKey

@Serializable data object SettingsScreen : NavKey

@Serializable data object SkillsScreen : NavKey

@Serializable data object CronJobsScreen : NavKey

@Serializable data object GatewayScreen : NavKey

@Serializable data object HistoryScreen : NavKey

@Serializable data object ProfilesScreen : NavKey

@Serializable data object ToolsetsScreen : NavKey

@Serializable data object AchievementsScreen : NavKey

@Serializable data object ConfigScreen : NavKey

@Serializable data object McpServersScreen : NavKey

@Serializable data object WebhooksScreen : NavKey

@Serializable data object ModelScreen : NavKey

@Serializable data object LogsScreen : NavKey

@Serializable data object PluginsScreen : NavKey

@Serializable data object ChannelsScreen : NavKey

@Serializable data object KeysScreen : NavKey

@Serializable data object SystemScreen : NavKey

@Serializable data object KanbanScreen : NavKey

@Serializable data object ProcessesScreen : NavKey

@Serializable data object ProvidersScreen : NavKey

@Serializable data object AnalyticsScreen : NavKey

@Serializable data object BillingScreen : NavKey

// ── Settings drill-down sub-pages ──────────────────────────────────────

@Serializable data object SettingsConnection : NavKey

@Serializable data object SettingsAppearance : NavKey

@Serializable data object SettingsChat : NavKey

@Serializable data object SettingsBehavior : NavKey

@Serializable data object SettingsAbout : NavKey
