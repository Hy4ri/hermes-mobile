package com.m57.hermescontrol

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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

@Composable
fun MainNavigation() {
    val hasToken = !AuthManager.getToken().isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else ConnectScreen

    val backStack = rememberNavBackStack(startScreen)
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val gesturesEnabled =
        currentScreen == ChatScreen || currentScreen == SkillsScreen || currentScreen == CronJobsScreen ||
            currentScreen == GatewayScreen || currentScreen == ProfilesScreen ||
            currentScreen == ToolsetsScreen || currentScreen == AchievementsScreen ||
            currentScreen == PairingScreen || currentScreen == ConfigScreen ||
            currentScreen == McpServersScreen || currentScreen == WebhooksScreen ||
            currentScreen == ModelScreen || currentScreen == LogsScreen ||
            currentScreen == PluginsScreen || currentScreen == ChannelsScreen ||
            currentScreen == KeysScreen || currentScreen == SystemScreen ||
            currentScreen == KanbanScreen
    val openDrawer = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Hermes Control",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    HorizontalDivider()
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Chat") },
                        selected = currentScreen == ChatScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ChatScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                        label = { Text("Skills") },
                        selected = currentScreen == SkillsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(SkillsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                        label = { Text("Cron Jobs") },
                        selected = currentScreen == CronJobsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(CronJobsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                        label = { Text("Profiles") },
                        selected = currentScreen == ProfilesScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ProfilesScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        label = { Text("Toolsets") },
                        selected = currentScreen == ToolsetsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ToolsetsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = { Text("Achievements") },
                        selected = currentScreen == AchievementsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(AchievementsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Devices, contentDescription = null) },
                        label = { Text("Pairing") },
                        selected = currentScreen == PairingScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(PairingScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                        label = { Text("Config") },
                        selected = currentScreen == ConfigScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ConfigScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                        label = { Text("MCP Servers") },
                        selected = currentScreen == McpServersScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(McpServersScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.CallSplit, contentDescription = null) },
                        label = { Text("Webhooks") },
                        selected = currentScreen == WebhooksScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(WebhooksScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Models") },
                        selected = currentScreen == ModelScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ModelScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("Gateway") },
                        selected = currentScreen == GatewayScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(GatewayScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("Logs") },
                        selected = currentScreen == LogsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(LogsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                        label = { Text("Plugins") },
                        selected = currentScreen == PluginsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(PluginsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Channels") },
                        selected = currentScreen == ChannelsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ChannelsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                        label = { Text("Keys") },
                        selected = currentScreen == KeysScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(KeysScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        label = { Text("System") },
                        selected = currentScreen == SystemScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(SystemScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                        label = { Text("Kanban") },
                        selected = currentScreen == KanbanScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(KanbanScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
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
                                backStack.add(SettingsScreen)
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
