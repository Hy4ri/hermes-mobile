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
import com.m57.hermescontrol.ui.achievements.AchievementsScreen as AchievementsScreenContent
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.channels.ChannelsScreen as ChannelsScreenContent
import com.m57.hermescontrol.ui.chat.ChatScreen as ChatScreenContent
import com.m57.hermescontrol.ui.config.ConfigScreen as ConfigScreenContent
import com.m57.hermescontrol.ui.cron.CronJobsScreen as CronJobsScreenContent
import com.m57.hermescontrol.ui.gateway.GatewayScreen as GatewayScreenContent
import com.m57.hermescontrol.ui.kanban.KanbanScreen as KanbanScreenContent
import com.m57.hermescontrol.ui.keys.KeysScreen as KeysScreenContent
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent
import com.m57.hermescontrol.ui.logs.LogsScreen as LogsScreenContent
import com.m57.hermescontrol.ui.mcp.McpServersScreen as McpServersScreenContent
import com.m57.hermescontrol.ui.model.ModelScreen as ModelScreenContent
import com.m57.hermescontrol.ui.pairing.PairingScreen as PairingScreenContent
import com.m57.hermescontrol.ui.plugins.PluginsScreen as PluginsScreenContent
import com.m57.hermescontrol.ui.profiles.ProfilesScreen as ProfilesScreenContent
import com.m57.hermescontrol.ui.sessions.SessionsScreen as HistoryScreenContent
import com.m57.hermescontrol.ui.settings.SettingsScreen as SettingsScreenContent
import com.m57.hermescontrol.ui.skills.SkillsScreen as SkillsScreenContent
import com.m57.hermescontrol.ui.system.SystemScreen as SystemScreenContent
import com.m57.hermescontrol.ui.toolsets.ToolsetsScreen as ToolsetsScreenContent
import com.m57.hermescontrol.ui.webhooks.WebhooksScreen as WebhooksScreenContent

// ── Bottom-nav item model ──────────────────────────────────────────────

private data class BottomNavItem(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** Master list of ALL screens available for bottom-nav selection. */
private val ALL_NAV_ITEMS: List<BottomNavItem> =
    listOf(
        BottomNavItem(ChatScreen, R.string.screen_chat, Icons.AutoMirrored.Filled.Chat),
        BottomNavItem(SkillsScreen, R.string.screen_skills, Icons.Filled.Extension),
        BottomNavItem(CronJobsScreen, R.string.screen_cron, Icons.Filled.Schedule),
        BottomNavItem(SystemScreen, R.string.screen_system, Icons.Filled.Info),
        BottomNavItem(SettingsScreen, R.string.screen_settings, Icons.Filled.Settings),
        BottomNavItem(ProfilesScreen, R.string.screen_profiles, Icons.Filled.AccountCircle),
        BottomNavItem(WebhooksScreen, R.string.screen_webhooks, Icons.Filled.Webhook),
        BottomNavItem(GatewayScreen, R.string.screen_gateway, Icons.Filled.Bolt),
        BottomNavItem(ToolsetsScreen, R.string.screen_toolsets, Icons.Filled.Build),
        BottomNavItem(PluginsScreen, R.string.screen_plugins, Icons.Filled.Memory),
        BottomNavItem(ConfigScreen, R.string.screen_config, Icons.Filled.Code),
        BottomNavItem(McpServersScreen, R.string.screen_mcp_servers, Icons.Filled.Dashboard),
        BottomNavItem(ModelScreen, R.string.screen_models, Icons.Filled.Psychology),
        BottomNavItem(PairingScreen, R.string.screen_pairing, Icons.Filled.Devices),
        BottomNavItem(KeysScreen, R.string.screen_keys, Icons.Filled.Key),
        BottomNavItem(ChannelsScreen, R.string.screen_channels, Icons.AutoMirrored.Filled.ListAlt),
        BottomNavItem(LogsScreen, R.string.screen_logs, Icons.Filled.HistoryEdu),
        BottomNavItem(KanbanScreen, R.string.screen_kanban, Icons.Filled.Dashboard),
        BottomNavItem(AchievementsScreen, R.string.screen_achievements, Icons.Filled.Info),
        BottomNavItem(HistoryScreen, R.string.screen_history, Icons.Filled.History),
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
        DrawerEntry(ChatScreen, R.string.screen_chat, Icons.AutoMirrored.Filled.Chat, DrawerSection.CONVERSE),
        DrawerEntry(HistoryScreen, R.string.screen_history, Icons.Filled.History, DrawerSection.CONVERSE),
        DrawerEntry(ProfilesScreen, R.string.screen_profiles, Icons.Filled.AccountCircle, DrawerSection.CONVERSE),
        // Automate
        DrawerEntry(CronJobsScreen, R.string.screen_cron, Icons.Filled.Schedule, DrawerSection.AUTOMATE),
        DrawerEntry(WebhooksScreen, R.string.screen_webhooks, Icons.Filled.Webhook, DrawerSection.AUTOMATE),
        DrawerEntry(GatewayScreen, R.string.screen_gateway, Icons.Filled.Bolt, DrawerSection.AUTOMATE),
        // Configure
        DrawerEntry(SkillsScreen, R.string.screen_skills, Icons.Filled.Extension, DrawerSection.CONFIGURE),
        DrawerEntry(ToolsetsScreen, R.string.screen_toolsets, Icons.Filled.Build, DrawerSection.CONFIGURE),
        DrawerEntry(PluginsScreen, R.string.screen_plugins, Icons.Filled.Memory, DrawerSection.CONFIGURE),
        DrawerEntry(ConfigScreen, R.string.screen_config, Icons.Filled.Code, DrawerSection.CONFIGURE),
        DrawerEntry(McpServersScreen, R.string.screen_mcp_servers, Icons.Filled.Dashboard, DrawerSection.CONFIGURE),
        DrawerEntry(ModelScreen, R.string.screen_models, Icons.Filled.Psychology, DrawerSection.CONFIGURE),
        DrawerEntry(PairingScreen, R.string.screen_pairing, Icons.Filled.Devices, DrawerSection.CONFIGURE),
        DrawerEntry(KeysScreen, R.string.screen_keys, Icons.Filled.Key, DrawerSection.CONFIGURE),
        DrawerEntry(
            ChannelsScreen,
            R.string.screen_channels,
            Icons.AutoMirrored.Filled.ListAlt,
            DrawerSection.CONFIGURE,
        ),
        // Inspect
        DrawerEntry(SystemScreen, R.string.screen_system, Icons.Filled.Info, DrawerSection.INSPECT),
        DrawerEntry(LogsScreen, R.string.screen_logs, Icons.Filled.HistoryEdu, DrawerSection.INSPECT),
        DrawerEntry(KanbanScreen, R.string.screen_kanban, Icons.Filled.Dashboard, DrawerSection.INSPECT),
        DrawerEntry(AchievementsScreen, R.string.screen_achievements, Icons.Filled.Info, DrawerSection.INSPECT),
        DrawerEntry(SettingsScreen, R.string.screen_settings, Icons.Filled.Settings, DrawerSection.INSPECT),
    )

private val DRAWER_GESTURE_SCREENS: Set<NavKey> = ALL_NAV_ITEMS.mapTo(mutableSetOf()) { it.key }

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<LandingScreen> {
        LandingScreenContent(
            onAuthLogin = {
                NavigationController.navigateTo(AuthLoginScreen)
            },
            onPairingLogin = {
                NavigationController.navigateTo(PairingScreen)
            },
        )
    }

    entry<AuthLoginScreen> {
        AuthLoginScreenContent(
            onConnected = {
                NavigationController.resetTo(ChatScreen)
            },
            onBack = {
                NavigationController.goBack()
            },
        )
    }

    entry<ChatScreen> {
        ChatScreenContent(
            onOpenDrawer = openDrawer,
            sessionId = sessionId,
        )
    }

    entry<HistoryScreen> {
        HistoryScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<SettingsScreen> {
        SettingsScreenContent(
            onBack = { NavigationController.goBack() },
        )
    }

    entry<SkillsScreen> {
        SkillsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<CronJobsScreen> {
        CronJobsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ProfilesScreen> {
        ProfilesScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ToolsetsScreen> {
        ToolsetsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<AchievementsScreen> {
        AchievementsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<PairingScreen> {
        PairingScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ConfigScreen> {
        ConfigScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<McpServersScreen> {
        McpServersScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<WebhooksScreen> {
        WebhooksScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ModelScreen> {
        ModelScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<GatewayScreen> {
        GatewayScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<LogsScreen> {
        LogsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<PluginsScreen> {
        PluginsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<ChannelsScreen> {
        ChannelsScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<KeysScreen> {
        KeysScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<SystemScreen> {
        SystemScreenContent(
            onOpenDrawer = openDrawer,
        )
    }

    entry<KanbanScreen> {
        KanbanScreenContent(
            onOpenDrawer = openDrawer,
        )
    }
}

@Composable
fun MainNavigation(sessionId: String? = null) {
    val token by AuthManager.tokenFlow.collectAsState()
    val hasToken = !token.isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else LandingScreen

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

    val drawerEntriesBySection = remember { DRAWER_ENTRIES.groupBy { it.section } }

    // Sync primary screens to NavigationController
    LaunchedEffect(bottomNavKeys) {
        NavigationController.updatePrimaryScreens(bottomNavKeys)
    }

    val showBottomBar =
        currentScreen != LandingScreen &&
            currentScreen != AuthLoginScreen
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
                    val statusColor =
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)

                            // green
                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.RECONNECTING,
                            -> Color(0xFFFFC107)

                            // yellow
                            ConnectionStatus.DISCONNECTED -> Color(0xFFF44336) // red
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
                        drawerEntriesBySection[section]?.forEach { entry ->
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
