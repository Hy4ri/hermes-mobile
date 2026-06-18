package com.m57.hermescontrol

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.achievements.AchievementsScreen as AchievementsScreenContent
import com.m57.hermescontrol.ui.channels.ChannelsScreen as ChannelsScreenContent
import com.m57.hermescontrol.ui.chat.ChatScreen as ChatScreenContent
import com.m57.hermescontrol.ui.config.ConfigScreen as ConfigScreenContent
import com.m57.hermescontrol.ui.connect.ConnectScreen as ConnectScreenContent
import com.m57.hermescontrol.ui.cron.CronJobsScreen as CronJobsScreenContent
import com.m57.hermescontrol.ui.gateway.GatewayScreen as GatewayScreenContent
import com.m57.hermescontrol.ui.kanban.KanbanScreen as KanbanScreenContent
import com.m57.hermescontrol.ui.keys.KeysScreen as KeysScreenContent
import com.m57.hermescontrol.ui.logs.LogsScreen as LogsScreenContent
import com.m57.hermescontrol.ui.mcp.McpServersScreen as McpServersScreenContent
import com.m57.hermescontrol.ui.model.ModelScreen as ModelScreenContent
import com.m57.hermescontrol.ui.pairing.PairingScreen as PairingScreenContent
import com.m57.hermescontrol.ui.plugins.PluginsScreen as PluginsScreenContent
import com.m57.hermescontrol.ui.profiles.ProfilesScreen as ProfilesScreenContent
import com.m57.hermescontrol.ui.settings.SettingsScreen as SettingsScreenContent
import com.m57.hermescontrol.ui.skills.SkillsScreen as SkillsScreenContent
import com.m57.hermescontrol.ui.system.SystemScreen as SystemScreenContent
import com.m57.hermescontrol.ui.toolsets.ToolsetsScreen as ToolsetsScreenContent
import com.m57.hermescontrol.ui.webhooks.WebhooksScreen as WebhooksScreenContent

/**
 * Drawer sections — the navigation soul of the app.
 *
 * Each section reflects how the user actually thinks about Hermes:
 *   CONVERSE  → talking to the agent
 *   AUTOMATE  → scheduled + push-driven actions
 *   CONFIGURE → settings, capabilities, integrations
 *   INSPECT   → observability, ops, achievements
 */
private enum class DrawerSection(val title: String) {
    CONVERSE("Converse"),
    AUTOMATE("Automate"),
    CONFIGURE("Configure"),
    INSPECT("Inspect"),
}

private data class DrawerEntry(
    val key: NavKey,
    val label: String,
    val icon: ImageVector,
    val section: DrawerSection,
)

private val DRAWER_ENTRIES =
    listOf(
        // Converse
        DrawerEntry(ChatScreen, "Chat", Icons.AutoMirrored.Filled.Chat, DrawerSection.CONVERSE),
        DrawerEntry(ProfilesScreen, "Profiles", Icons.Filled.AccountCircle, DrawerSection.CONVERSE),
        // Automate
        DrawerEntry(CronJobsScreen, "Cron Jobs", Icons.Filled.Schedule, DrawerSection.AUTOMATE),
        DrawerEntry(WebhooksScreen, "Webhooks", Icons.Filled.Webhook, DrawerSection.AUTOMATE),
        DrawerEntry(GatewayScreen, "Gateway", Icons.Filled.Bolt, DrawerSection.AUTOMATE),
        // Configure
        DrawerEntry(SkillsScreen, "Skills", Icons.Filled.Extension, DrawerSection.CONFIGURE),
        DrawerEntry(ToolsetsScreen, "Toolsets", Icons.Filled.Build, DrawerSection.CONFIGURE),
        DrawerEntry(PluginsScreen, "Plugins", Icons.Filled.Memory, DrawerSection.CONFIGURE),
        DrawerEntry(ConfigScreen, "Config", Icons.Filled.Code, DrawerSection.CONFIGURE),
        DrawerEntry(McpServersScreen, "MCP Servers", Icons.Filled.Dashboard, DrawerSection.CONFIGURE),
        DrawerEntry(ModelScreen, "Models", Icons.Filled.Settings, DrawerSection.CONFIGURE),
        DrawerEntry(PairingScreen, "Pairing", Icons.Filled.Devices, DrawerSection.CONFIGURE),
        DrawerEntry(KeysScreen, "Keys", Icons.Filled.Key, DrawerSection.CONFIGURE),
        DrawerEntry(ChannelsScreen, "Channels", Icons.Filled.ListAlt, DrawerSection.CONFIGURE),
        // Inspect
        DrawerEntry(SystemScreen, "System", Icons.Filled.Info, DrawerSection.INSPECT),
        DrawerEntry(LogsScreen, "Logs", Icons.Filled.HistoryEdu, DrawerSection.INSPECT),
        DrawerEntry(KanbanScreen, "Kanban", Icons.Filled.Dashboard, DrawerSection.INSPECT),
        DrawerEntry(AchievementsScreen, "Achievements", Icons.Filled.Info, DrawerSection.INSPECT),
    )

/**
 * Screens where swipe-to-open-drawer is allowed.
 *
 * Settings is intentionally excluded because it's pushed onto the stack
 * (uses Back, not drawer); Connect is the entry gate and has no drawer.
 */
private val DRAWER_GESTURE_SCREENS: Set<NavKey> =
    DRAWER_ENTRIES.map { it.key }.toSet()

@Composable
fun MainNavigation() {
    val hasToken = !AuthManager.getToken().isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else ConnectScreen

    val backStack = rememberNavBackStack(startScreen)
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val gesturesEnabled = currentScreen in DRAWER_GESTURE_SCREENS
    val openDrawer = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    // Brand header
                    Text(
                        text = "⚡ Hermes",
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "AI Agent Control",
                        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp, end = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Render grouped entries
                    for (section in DrawerSection.entries) {
                        Text(
                            text = section.title.uppercase(),
                            modifier =
                                Modifier.padding(
                                    start = 20.dp,
                                    top = 16.dp,
                                    bottom = 6.dp,
                                    end = 16.dp,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DRAWER_ENTRIES
                            .filter { it.section == section }
                            .forEach { entry ->
                                NavigationDrawerItem(
                                    icon = { Icon(entry.icon, contentDescription = null) },
                                    label = { Text(entry.label) },
                                    selected = currentScreen == entry.key,
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        NavigationController.navigateTo(entry.key)
                                    },
                                    colors =
                                        NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Settings is always at the bottom — not a section sibling
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        selected = currentScreen == SettingsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(SettingsScreen)
                        },
                        colors =
                            NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider =
                entryProvider {
                    entry<ConnectScreen> {
                        ConnectScreenContent(
                            onConnected = {
                                backStack.clear()
                                backStack.add(ChatScreen)
                            },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }

                    entry<ChatScreen> {
                        ChatScreenContent(
                            onNavigateToSettings = {
                                // B7 (Jun 18 2026): previously used
                                // `backStack.add(SettingsScreen)` directly,
                                // which bypasses NavigationController's
                                // navigation guard. The drawer Settings path
                                // goes through NavigationController which
                                // deduplicates and prevents stacking duplicate
                                // SettingsScreen entries when user rapidly
                                // taps the gear icon. Use the same path for
                                // consistency and to recover if a duplicate
                                // SettingsScreen entry was the source of the
                                // "page unresponsive" symptom reported when
                                // reaching Settings from the topbar.
                                NavigationController.navigateTo(SettingsScreen)
                            },
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<SettingsScreen> {
                        SettingsScreenContent(
                            onBack = {
                                backStack.removeLastOrNull()
                            },
                        )
                    }

                    entry<SkillsScreen> {
                        SkillsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<CronJobsScreen> {
                        CronJobsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<ProfilesScreen> {
                        ProfilesScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<ToolsetsScreen> {
                        ToolsetsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<AchievementsScreen> {
                        AchievementsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<PairingScreen> {
                        PairingScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<ConfigScreen> {
                        ConfigScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<McpServersScreen> {
                        McpServersScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<WebhooksScreen> {
                        WebhooksScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<ModelScreen> {
                        ModelScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<GatewayScreen> {
                        GatewayScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<LogsScreen> {
                        LogsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<PluginsScreen> {
                        PluginsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<ChannelsScreen> {
                        ChannelsScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<KeysScreen> {
                        KeysScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<SystemScreen> {
                        SystemScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }

                    entry<KanbanScreen> {
                        KanbanScreenContent(
                            onOpenDrawer = { openDrawer() },
                        )
                    }
                },
        )
    }
}
