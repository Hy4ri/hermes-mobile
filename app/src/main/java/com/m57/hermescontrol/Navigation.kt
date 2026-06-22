package com.m57.hermescontrol

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.achievements.AchievementsScreen
import com.m57.hermescontrol.ui.channels.ChannelsScreen
import com.m57.hermescontrol.ui.chat.ChatScreen
import com.m57.hermescontrol.ui.config.ConfigScreen
import com.m57.hermescontrol.ui.connect.ConnectScreen
import com.m57.hermescontrol.ui.cron.CronJobsScreen
import com.m57.hermescontrol.ui.gateway.GatewayScreen
import com.m57.hermescontrol.ui.kanban.KanbanScreen
import com.m57.hermescontrol.ui.keys.KeysScreen
import com.m57.hermescontrol.ui.logs.LogsScreen
import com.m57.hermescontrol.ui.mcp.McpServersScreen
import com.m57.hermescontrol.ui.model.ModelScreen
import com.m57.hermescontrol.ui.pairing.PairingScreen
import com.m57.hermescontrol.ui.plugins.PluginsScreen
import com.m57.hermescontrol.ui.profiles.ProfilesScreen
import com.m57.hermescontrol.ui.sessions.SessionsScreen
import com.m57.hermescontrol.ui.settings.SettingsScreen
import com.m57.hermescontrol.ui.skills.SkillsScreen
import com.m57.hermescontrol.ui.system.SystemScreen
import com.m57.hermescontrol.ui.toolsets.ToolsetsScreen
import com.m57.hermescontrol.ui.webhooks.WebhooksScreen

// ── Bottom-nav item model ──────────────────────────────────────────────

private data class BottomNavItem(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** Master list of ALL screens available for bottom-nav selection. */
private val ALL_NAV_ITEMS: List<BottomNavItem> =
    listOf(
        BottomNavItem(ChatScreenKey, R.string.screen_chat, Icons.AutoMirrored.Filled.Chat),
        BottomNavItem(SkillsScreenKey, R.string.screen_skills, Icons.Filled.Extension),
        BottomNavItem(CronJobsScreenKey, R.string.screen_cron, Icons.Filled.Schedule),
        BottomNavItem(SystemScreenKey, R.string.screen_system, Icons.Filled.Info),
        BottomNavItem(SettingsScreenKey, R.string.screen_settings, Icons.Filled.Settings),
        BottomNavItem(ProfilesScreenKey, R.string.screen_profiles, Icons.Filled.AccountCircle),
        BottomNavItem(WebhooksScreenKey, R.string.screen_webhooks, Icons.Filled.Webhook),
        BottomNavItem(GatewayScreenKey, R.string.screen_gateway, Icons.Filled.Bolt),
        BottomNavItem(ToolsetsScreenKey, R.string.screen_toolsets, Icons.Filled.Build),
        BottomNavItem(PluginsScreenKey, R.string.screen_plugins, Icons.Filled.Memory),
        BottomNavItem(ConfigScreenKey, R.string.screen_config, Icons.Filled.Code),
        BottomNavItem(McpServersScreenKey, R.string.screen_mcp_servers, Icons.Filled.Dashboard),
        BottomNavItem(ModelScreenKey, R.string.screen_models, Icons.Filled.Psychology),
        BottomNavItem(PairingScreenKey, R.string.screen_pairing, Icons.Filled.Devices),
        BottomNavItem(KeysScreenKey, R.string.screen_keys, Icons.Filled.Key),
        BottomNavItem(ChannelsScreenKey, R.string.screen_channels, Icons.AutoMirrored.Filled.ListAlt),
        BottomNavItem(LogsScreenKey, R.string.screen_logs, Icons.Filled.HistoryEdu),
        BottomNavItem(KanbanScreenKey, R.string.screen_kanban, Icons.Filled.Dashboard),
        BottomNavItem(AchievementsScreenKey, R.string.screen_achievements, Icons.Filled.Info),
        BottomNavItem(HistoryScreenKey, R.string.screen_history, Icons.Filled.History),
    )

/** Lookup: data-object simple name → NavKey (used by bottom-nav config). */
private val NAV_KEY_BY_NAME: Map<String, NavKey> =
    ALL_NAV_ITEMS.mapNotNull { it.key::class.simpleName?.let { name -> name to it.key } }.toMap()

/** Resolve a persisted list of nav-item names to BottomNavItem instances. */
private fun resolveBottomNavItems(names: List<String>): List<BottomNavItem> =
    names.mapNotNull { name -> NAV_KEY_BY_NAME[name]?.let { key -> ALL_NAV_ITEMS.first { it.key == key } } }

// ── Drawer sections ────────────────────────────────────────────────────

private enum class DrawerSection(
    @param:StringRes val titleRes: Int,
) {
    CONVERSE(R.string.nav_drawer_section_converse),
    AUTOMATE(R.string.nav_drawer_section_automate),
    CONFIGURE(R.string.nav_drawer_section_configure),
    INSPECT(R.string.nav_drawer_section_inspect),
}

private data class DrawerEntry(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val section: DrawerSection,
)

private val DRAWER_ENTRIES =
    listOf(
        // Converse
        DrawerEntry(ChatScreenKey, R.string.screen_chat, Icons.AutoMirrored.Filled.Chat, DrawerSection.CONVERSE),
        DrawerEntry(HistoryScreenKey, R.string.screen_history, Icons.Filled.History, DrawerSection.CONVERSE),
        DrawerEntry(ProfilesScreenKey, R.string.screen_profiles, Icons.Filled.AccountCircle, DrawerSection.CONVERSE),
        // Automate
        DrawerEntry(CronJobsScreenKey, R.string.screen_cron, Icons.Filled.Schedule, DrawerSection.AUTOMATE),
        DrawerEntry(WebhooksScreenKey, R.string.screen_webhooks, Icons.Filled.Webhook, DrawerSection.AUTOMATE),
        DrawerEntry(GatewayScreenKey, R.string.screen_gateway, Icons.Filled.Bolt, DrawerSection.AUTOMATE),
        // Configure
        DrawerEntry(SkillsScreenKey, R.string.screen_skills, Icons.Filled.Extension, DrawerSection.CONFIGURE),
        DrawerEntry(ToolsetsScreenKey, R.string.screen_toolsets, Icons.Filled.Build, DrawerSection.CONFIGURE),
        DrawerEntry(PluginsScreenKey, R.string.screen_plugins, Icons.Filled.Memory, DrawerSection.CONFIGURE),
        DrawerEntry(ConfigScreenKey, R.string.screen_config, Icons.Filled.Code, DrawerSection.CONFIGURE),
        DrawerEntry(McpServersScreenKey, R.string.screen_mcp_servers, Icons.Filled.Dashboard, DrawerSection.CONFIGURE),
        DrawerEntry(ModelScreenKey, R.string.screen_models, Icons.Filled.Psychology, DrawerSection.CONFIGURE),
        DrawerEntry(PairingScreenKey, R.string.screen_pairing, Icons.Filled.Devices, DrawerSection.CONFIGURE),
        DrawerEntry(KeysScreenKey, R.string.screen_keys, Icons.Filled.Key, DrawerSection.CONFIGURE),
        DrawerEntry(
            ChannelsScreenKey,
            R.string.screen_channels,
            Icons.AutoMirrored.Filled.ListAlt,
            DrawerSection.CONFIGURE,
        ),
        // Inspect
        DrawerEntry(SystemScreenKey, R.string.screen_system, Icons.Filled.Info, DrawerSection.INSPECT),
        DrawerEntry(LogsScreenKey, R.string.screen_logs, Icons.Filled.HistoryEdu, DrawerSection.INSPECT),
        DrawerEntry(KanbanScreenKey, R.string.screen_kanban, Icons.Filled.Dashboard, DrawerSection.INSPECT),
        DrawerEntry(AchievementsScreenKey, R.string.screen_achievements, Icons.Filled.Info, DrawerSection.INSPECT),
        DrawerEntry(SettingsScreenKey, R.string.screen_settings, Icons.Filled.Settings, DrawerSection.INSPECT),
    )

private val DRAWER_GESTURE_SCREENS: Set<NavKey> = ALL_NAV_ITEMS.mapTo(mutableSetOf()) { it.key }

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<ConnectScreenKey> {
        ConnectScreen(
            onConnected = {
                NavigationController.resetTo(ChatScreenKey)
            },
            modifier = Modifier.safeDrawingPadding(),
        )
    }

    entry<ChatScreenKey> {
        ChatScreen(
            onOpenDrawer = openDrawer,
            sessionId = sessionId,
        )
    }

    entry<HistoryScreenKey> {
        SessionsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<SettingsScreenKey> {
        SettingsScreen(
            onBack = { NavigationController.goBack() },
        )
    }

    entry<SkillsScreenKey> {
        SkillsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<CronJobsScreenKey> {
        CronJobsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ProfilesScreenKey> {
        ProfilesScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ToolsetsScreenKey> {
        ToolsetsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<AchievementsScreenKey> {
        AchievementsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<PairingScreenKey> {
        PairingScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ConfigScreenKey> {
        ConfigScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<McpServersScreenKey> {
        McpServersScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<WebhooksScreenKey> {
        WebhooksScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ModelScreenKey> {
        ModelScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<GatewayScreenKey> {
        GatewayScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<LogsScreenKey> {
        LogsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<PluginsScreenKey> {
        PluginsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ChannelsScreenKey> {
        ChannelsScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<KeysScreenKey> {
        KeysScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<SystemScreenKey> {
        SystemScreen(
            onOpenDrawer = openDrawer,
        )
    }

    entry<KanbanScreenKey> {
        KanbanScreen(
            onOpenDrawer = openDrawer,
        )
    }
}

@Composable
fun MainNavigation(sessionId: String? = null) {
    val hasToken = !AuthManager.getToken().isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreenKey else ConnectScreenKey

    val backStack = rememberNavBackStack(startScreen)
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Read dynamic bottom-nav config.
    // AuthManager.bottomNavItemsFlow is a StateFlow that emits updates when the user
    // customises items in Settings. Using collectAsState() ensures the NavigationBar
    // recomposes and reflects choices instantly.
    val bottomNavItemsState by AuthManager.bottomNavItemsFlow.collectAsState()
    val bottomNavDisplayMode by AuthManager.bottomNavDisplayModeFlow.collectAsState()
    val bottomNavItems = resolveBottomNavItems(bottomNavItemsState)
    val bottomNavKeys = remember(bottomNavItems) { bottomNavItems.mapTo(mutableSetOf()) { it.key } }

    // Sync primary screens to NavigationController
    LaunchedEffect(bottomNavKeys) {
        NavigationController.updatePrimaryScreens(bottomNavKeys)
    }

    val showBottomBar = currentScreen != ConnectScreenKey
    val gesturesEnabled = currentScreen in DRAWER_GESTURE_SCREENS
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

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
                    val statusColors = com.m57.hermescontrol.theme.LocalHermesStatusColors.current
                    val statusColor =
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> statusColors.success

                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.RECONNECTING,
                            -> statusColors.warning

                            ConnectionStatus.DISCONNECTED -> statusColors.error
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nav_drawer_title),
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
                        text = stringResource(R.string.nav_drawer_subtitle),
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, end = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    for (section in DrawerSection.entries) {
                        Text(
                            text = stringResource(section.titleRes).uppercase(),
                            modifier =
                                Modifier.padding(
                                    start = 16.dp,
                                    top = 8.dp,
                                    bottom = 4.dp,
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
                                    label = { Text(stringResource(entry.labelRes)) },
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
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 8.dp, vertical = 1.dp)
                                            .testTag(
                                                "drawer_${entry.key::class.simpleName?.lowercase()?.removeSuffix(
                                                    "screen",
                                                ) ?: ""}",
                                            ),
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
                if (showBottomBar) {
                    val barHeight =
                        when (bottomNavDisplayMode) {
                            BottomNavDisplayMode.ICON_ONLY -> 56.dp
                            BottomNavDisplayMode.TEXT_ONLY -> 44.dp
                            BottomNavDisplayMode.ICON_AND_TEXT -> 80.dp
                        }
                    NavigationBar(
                        modifier = Modifier.height(barHeight),
                    ) {
                        bottomNavItems.forEach { item ->
                            val showIcon =
                                bottomNavDisplayMode == BottomNavDisplayMode.ICON_AND_TEXT ||
                                    bottomNavDisplayMode == BottomNavDisplayMode.ICON_ONLY
                            val showLabel =
                                bottomNavDisplayMode == BottomNavDisplayMode.ICON_AND_TEXT ||
                                    bottomNavDisplayMode == BottomNavDisplayMode.TEXT_ONLY

                            val isSelected = currentScreen == item.key

                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { NavigationController.navigateTo(item.key) },
                                colors =
                                    if (bottomNavDisplayMode == BottomNavDisplayMode.TEXT_ONLY) {
                                        NavigationBarItemDefaults.colors(
                                            indicatorColor = Color.Transparent,
                                        )
                                    } else {
                                        NavigationBarItemDefaults.colors()
                                    },
                                icon = {
                                    if (showIcon) {
                                        Icon(item.icon, contentDescription = stringResource(item.labelRes))
                                    } else {
                                        Text(
                                            text = stringResource(item.labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                },
                                label =
                                    if (showLabel && showIcon) {
                                        { Text(stringResource(item.labelRes)) }
                                    } else {
                                        null
                                    },
                                modifier =
                                    Modifier.testTag(
                                        "nav_${item.key::class.simpleName?.lowercase()?.removeSuffix("screen") ?: ""}",
                                    ),
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            NavDisplay(
                backStack = backStack,
                onBack = { NavigationController.goBack() },
                entryProvider =
                    appEntryProvider(sessionId, openDrawer),
                modifier =
                    Modifier
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues)
                        .fillMaxSize(),
            )
        }
    }
}
