package com.m57.hermescontrol

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
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
import com.m57.hermescontrol.ui.chat.ChatScreen as ChatScreenContent
import com.m57.hermescontrol.ui.config.ConfigScreen as ConfigScreenContent
import com.m57.hermescontrol.ui.connect.ConnectScreen as ConnectScreenContent
import com.m57.hermescontrol.ui.cron.CronJobsScreen as CronJobsScreenContent
import com.m57.hermescontrol.ui.gateway.GatewayScreen as GatewayScreenContent
import com.m57.hermescontrol.ui.mcp.McpServersScreen as McpServersScreenContent
import com.m57.hermescontrol.ui.model.ModelScreen as ModelScreenContent
import com.m57.hermescontrol.ui.pairing.PairingScreen as PairingScreenContent
import com.m57.hermescontrol.ui.profiles.ProfilesScreen as ProfilesScreenContent
import com.m57.hermescontrol.ui.settings.SettingsScreen as SettingsScreenContent
import com.m57.hermescontrol.ui.skills.SkillsScreen as SkillsScreenContent
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
            currentScreen == ModelScreen
    val openDrawer = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet {
                    Text(
                        text = "Hermes Control",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("Chat") },
                        selected = currentScreen == ChatScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ChatScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Skills") },
                        selected = currentScreen == SkillsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(SkillsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Cron Jobs") },
                        selected = currentScreen == CronJobsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(CronJobsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Profiles") },
                        selected = currentScreen == ProfilesScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ProfilesScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Toolsets") },
                        selected = currentScreen == ToolsetsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ToolsetsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Achievements") },
                        selected = currentScreen == AchievementsScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(AchievementsScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Pairing") },
                        selected = currentScreen == PairingScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(PairingScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Config") },
                        selected = currentScreen == ConfigScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ConfigScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("MCP Servers") },
                        selected = currentScreen == McpServersScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(McpServersScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Webhooks") },
                        selected = currentScreen == WebhooksScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(WebhooksScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Models") },
                        selected = currentScreen == ModelScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(ModelScreen)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Gateway") },
                        selected = currentScreen == GatewayScreen,
                        onClick = {
                            scope.launch { drawerState.close() }
                            NavigationController.navigateTo(GatewayScreen)
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
                },
        )
    }
}
