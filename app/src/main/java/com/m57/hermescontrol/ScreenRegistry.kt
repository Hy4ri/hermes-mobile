package com.m57.hermescontrol

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ui.achievements.AchievementsScreen as AchievementsScreenContent
import com.m57.hermescontrol.ui.analytics.AnalyticsScreen as AnalyticsScreenContent
import com.m57.hermescontrol.ui.channels.ChannelsScreen as ChannelsScreenContent
import com.m57.hermescontrol.ui.chat.ChatScreen as ChatScreenContent
import com.m57.hermescontrol.ui.config.ConfigScreen as ConfigScreenContent
import com.m57.hermescontrol.ui.cron.CronJobsScreen as CronJobsScreenContent
import com.m57.hermescontrol.ui.gateway.GatewayScreen as GatewayScreenContent
import com.m57.hermescontrol.ui.kanban.KanbanScreen as KanbanScreenContent
import com.m57.hermescontrol.ui.keys.KeysScreen as KeysScreenContent
import com.m57.hermescontrol.ui.logs.LogsScreen as LogsScreenContent
import com.m57.hermescontrol.ui.mcp.McpServersScreen as McpServersScreenContent
import com.m57.hermescontrol.ui.model.ModelScreen as ModelScreenContent
import com.m57.hermescontrol.ui.pairing.PairingScreen as PairingScreenContent
import com.m57.hermescontrol.ui.plugins.PluginsScreen as PluginsScreenContent
import com.m57.hermescontrol.ui.process.ProcessesScreen as ProcessesScreenContent
import com.m57.hermescontrol.ui.profiles.ProfilesScreen as ProfilesScreenContent
import com.m57.hermescontrol.ui.sessions.SessionsScreen as HistoryScreenContent
import com.m57.hermescontrol.ui.settings.SettingsScreen as SettingsScreenContent
import com.m57.hermescontrol.ui.skills.SkillsScreen as SkillsScreenContent
import com.m57.hermescontrol.ui.system.SystemScreen as SystemScreenContent
import com.m57.hermescontrol.ui.toolsets.ToolsetsScreen as ToolsetsScreenContent
import com.m57.hermescontrol.ui.webhooks.WebhooksScreen as WebhooksScreenContent

enum class DrawerSection(
    @param:StringRes val titleRes: Int,
) {
    CONVERSE(R.string.nav_drawer_section_converse),
    AUTOMATE(R.string.nav_drawer_section_automate),
    CONFIGURE(R.string.nav_drawer_section_configure),
    INSPECT(R.string.nav_drawer_section_inspect),
}

data class ScreenDefinition(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val drawerSection: DrawerSection?,
    val content: @Composable (sessionId: String?, openDrawer: () -> Unit) -> Unit,
)

object ScreenRegistry {
    val ALL_SCREENS =
        listOf(
            ScreenDefinition(
                ChatScreen,
                R.string.screen_chat,
                Icons.AutoMirrored.Filled.Chat,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> ChatScreenContent(onOpenDrawer = openDrawer, sessionId = sessionId) },
            ScreenDefinition(
                HistoryScreen,
                R.string.screen_history,
                Icons.Filled.History,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> HistoryScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ProfilesScreen,
                R.string.screen_profiles,
                Icons.Filled.AccountCircle,
                DrawerSection.CONVERSE,
            ) { sessionId, openDrawer -> ProfilesScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                CronJobsScreen,
                R.string.screen_cron,
                Icons.Filled.Schedule,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> CronJobsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                WebhooksScreen,
                R.string.screen_webhooks,
                Icons.Filled.Webhook,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> WebhooksScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                GatewayScreen,
                R.string.screen_gateway,
                Icons.Filled.Bolt,
                DrawerSection.AUTOMATE,
            ) { sessionId, openDrawer -> GatewayScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                SkillsScreen,
                R.string.screen_skills,
                Icons.Filled.Extension,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> SkillsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ToolsetsScreen,
                R.string.screen_toolsets,
                Icons.Filled.Build,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ToolsetsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                PluginsScreen,
                R.string.screen_plugins,
                Icons.Filled.Memory,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> PluginsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ConfigScreen,
                R.string.screen_config,
                Icons.Filled.Code,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ConfigScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                McpServersScreen,
                R.string.screen_mcp_servers,
                Icons.Filled.Dashboard,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> McpServersScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ModelScreen,
                R.string.screen_models,
                Icons.Filled.Psychology,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ModelScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                PairingScreen,
                R.string.screen_pairing,
                Icons.Filled.Devices,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> PairingScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                KeysScreen,
                R.string.screen_keys,
                Icons.Filled.Key,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> KeysScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ChannelsScreen,
                R.string.screen_channels,
                Icons.AutoMirrored.Filled.ListAlt,
                DrawerSection.CONFIGURE,
            ) { sessionId, openDrawer -> ChannelsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                SystemScreen,
                R.string.screen_system,
                Icons.Filled.Info,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> SystemScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                LogsScreen,
                R.string.screen_logs,
                Icons.Filled.HistoryEdu,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> LogsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                ProcessesScreen,
                R.string.screen_processes,
                Icons.Filled.Memory,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> ProcessesScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                AnalyticsScreen,
                R.string.screen_analytics,
                Icons.Filled.BarChart,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> AnalyticsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                KanbanScreen,
                R.string.screen_kanban,
                Icons.Filled.Dashboard,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> KanbanScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                AchievementsScreen,
                R.string.screen_achievements,
                Icons.Filled.Info,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer -> AchievementsScreenContent(onOpenDrawer = openDrawer) },
            ScreenDefinition(
                SettingsScreen,
                R.string.screen_settings,
                Icons.Filled.Settings,
                DrawerSection.INSPECT,
            ) { sessionId, openDrawer ->
                SettingsScreenContent(
                    onBack = {
                        NavigationController.goBack()
                    },
                    onNavigateToLogin = {
                        NavigationController.navigateTo(AuthLoginScreen)
                    },
                )
            },
        )
}
