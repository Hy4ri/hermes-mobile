package com.m57.hermescontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
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

// ── Bottom-nav item model ──────────────────────────────────────────────

private data class BottomNavItem(
    val key: NavKey,
    val label: String,
    val icon: ImageVector,
)

/** Master list of ALL screens available for bottom-nav selection. */
private val ALL_NAV_ITEMS: List<BottomNavItem> =
    listOf(
        BottomNavItem(ChatScreen, "Chat", Icons.AutoMirrored.Filled.Chat),
        BottomNavItem(SkillsScreen, "Skills", Icons.Filled.Extension),
        BottomNavItem(CronJobsScreen, "Cron", Icons.Filled.Schedule),
        BottomNavItem(SystemScreen, "System", Icons.Filled.Info),
        BottomNavItem(SettingsScreen, "Settings", Icons.Filled.Settings),
        BottomNavItem(ProfilesScreen, "Profiles", Icons.Filled.AccountCircle),
        BottomNavItem(WebhooksScreen, "Webhooks", Icons.Filled.Webhook),
        BottomNavItem(GatewayScreen, "Gateway", Icons.Filled.Bolt),
        BottomNavItem(ToolsetsScreen, "Toolsets", Icons.Filled.Build),
        BottomNavItem(PluginsScreen, "Plugins", Icons.Filled.Memory),
        BottomNavItem(ConfigScreen, "Config", Icons.Filled.Code),
        BottomNavItem(McpServersScreen, "MCP Servers", Icons.Filled.Dashboard),
        BottomNavItem(ModelScreen, "Models", Icons.Filled.Settings),
        BottomNavItem(PairingScreen, "Pairing", Icons.Filled.Devices),
        BottomNavItem(KeysScreen, "Keys", Icons.Filled.Key),
        BottomNavItem(ChannelsScreen, "Channels", Icons.Filled.ListAlt),
        BottomNavItem(LogsScreen, "Logs", Icons.Filled.HistoryEdu),
        BottomNavItem(KanbanScreen, "Kanban", Icons.Filled.Dashboard),
        BottomNavItem(AchievementsScreen, "Achievements", Icons.Filled.Info),
    )

/** Lookup: data-object simple name → NavKey (used by bottom-nav config). */
private val NAV_KEY_BY_NAME: Map<String, NavKey> =
    ALL_NAV_ITEMS.associate { it.key::class.simpleName!! to it.key }

/** Resolve a persisted list of nav-item names to BottomNavItem instances. */
private fun resolveBottomNavItems(names: List<String>): List<BottomNavItem> =
    names.mapNotNull { name -> NAV_KEY_BY_NAME[name]?.let { key -> ALL_NAV_ITEMS.first { it.key == key } } }

// ── Drawer sections for secondary screens ──────────────────────────────

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
        DrawerEntry(ProfilesScreen, "Profiles", Icons.Filled.AccountCircle, DrawerSection.CONVERSE),
        // Automate
        DrawerEntry(WebhooksScreen, "Webhooks", Icons.Filled.Webhook, DrawerSection.AUTOMATE),
        DrawerEntry(GatewayScreen, "Gateway", Icons.Filled.Bolt, DrawerSection.AUTOMATE),
        // Configure
        DrawerEntry(ToolsetsScreen, "Toolsets", Icons.Filled.Build, DrawerSection.CONFIGURE),
        DrawerEntry(PluginsScreen, "Plugins", Icons.Filled.Memory, DrawerSection.CONFIGURE),
        DrawerEntry(ConfigScreen, "Config", Icons.Filled.Code, DrawerSection.CONFIGURE),
        DrawerEntry(McpServersScreen, "MCP Servers", Icons.Filled.Dashboard, DrawerSection.CONFIGURE),
        DrawerEntry(ModelScreen, "Models", Icons.Filled.Settings, DrawerSection.CONFIGURE),
        DrawerEntry(PairingScreen, "Pairing", Icons.Filled.Devices, DrawerSection.CONFIGURE),
        DrawerEntry(KeysScreen, "Keys", Icons.Filled.Key, DrawerSection.CONFIGURE),
        DrawerEntry(ChannelsScreen, "Channels", Icons.Filled.ListAlt, DrawerSection.CONFIGURE),
        // Inspect
        DrawerEntry(LogsScreen, "Logs", Icons.Filled.HistoryEdu, DrawerSection.INSPECT),
        DrawerEntry(KanbanScreen, "Kanban", Icons.Filled.Dashboard, DrawerSection.INSPECT),
        DrawerEntry(AchievementsScreen, "Achievements", Icons.Filled.Info, DrawerSection.INSPECT),
    )

private val DRAWER_GESTURE_SCREENS: Set<NavKey> = ALL_NAV_ITEMS.map { it.key }.toSet()

@Composable
fun MainNavigation() {
    val hasToken = !AuthManager.getToken().isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else ConnectScreen

    val backStack = rememberNavBackStack(startScreen)
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Read dynamic bottom-nav config
    val bottomNavItems = remember { resolveBottomNavItems(AuthManager.getBottomNavItems()) }
    val bottomNavKeys = remember(bottomNavItems) { bottomNavItems.map { it.key }.toSet() }

    // Sync primary screens to NavigationController
    LaunchedEffect(bottomNavKeys) {
        NavigationController.updatePrimaryScreens(bottomNavKeys)
    }

    val isPrimaryScreen = currentScreen in bottomNavKeys
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
                    // Brand header with connection status
                    val connectionStatus by HermesWsClient.connectionStatus.collectAsState()
                    val statusColor =
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) // green
                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.RECONNECTING,
                            -> Color(0xFFFFC107) // yellow
                            ConnectionStatus.DISCONNECTED -> Color(0xFFF44336) // red
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
                    ) {
                        Text(
                            text = "⚡ Hermes",
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .background(color = statusColor, shape = CircleShape),
                        )
                    }
                    Text(
                        text = "AI Agent Control",
                        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp, end = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                if (isPrimaryScreen) {
                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            NavigationBarItem(
                                selected = currentScreen == item.key,
                                onClick = { NavigationController.navigateTo(item.key) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
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
                                    // B7 (Jun 18 2026): route through
                                    // NavigationController to prevent
                                    // stacking duplicate entries.
                                    NavigationController.navigateTo(SettingsScreen)
                                },
                                onOpenDrawer = { openDrawer() },
                            )
                        }

                        entry<SettingsScreen> {
                            SettingsScreenContent(
                                onBack = { backStack.removeLastOrNull() },
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
                modifier =
                    Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
            )
        }
    }
}
