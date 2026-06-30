package com.m57.hermescontrol

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent
import com.m57.hermescontrol.ui.pairing.PairingCodeEntryScreen as PairingCodeEntryScreenContent

private fun resolveBottomNavItems(names: List<String>): List<ScreenDefinition> =
    names.mapNotNull { name -> ScreenRegistry.ALL_SCREENS.firstOrNull { it.key::class.simpleName == name } }

private val DRAWER_GESTURE_SCREENS: Set<NavKey> = ScreenRegistry.ALL_SCREENS.mapTo(mutableSetOf()) { it.key }

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<LandingScreen> {
        // B7 (Jun 30 2026, kanban t_424): route landing screen buttons through navigateTo to prevent duplicate screens
        LandingScreenContent(
            onAuthLogin = {
                NavigationController.navigateTo(AuthLoginScreen)
            },
            onPairingLogin = {
                NavigationController.navigateTo(PairingCodeEntryScreen)
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

    entry<PairingCodeEntryScreen> {
        PairingCodeEntryScreenContent(
            onConnected = {
                NavigationController.resetTo(ChatScreen)
            },
            onBack = {
                NavigationController.goBack()
            },
        )
    }

    ScreenRegistry.ALL_SCREENS.forEach { screen ->
        addEntryProvider(clazz = screen.key::class) {
            screen.content(sessionId, openDrawer)
        }
    }
}

@Composable
fun MainNavigation(sessionId: String? = null) {
    val token by AuthManager.tokenFlow.collectAsState()
    val hasToken = !token.isNullOrBlank()
    val startScreen: NavKey = if (hasToken) ChatScreen else LandingScreen

    val backStack = remember(startScreen) { NavBackStack(startScreen) }
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val bottomNavItemsState by AuthManager.bottomNavItemsFlow.collectAsState()
    val bottomNavDisplayMode by AuthManager.bottomNavDisplayModeFlow.collectAsState()
    val bottomNavItems = resolveBottomNavItems(bottomNavItemsState)
    val bottomNavKeys = remember(bottomNavItems) { bottomNavItems.mapTo(mutableSetOf()) { it.key } }

    LaunchedEffect(bottomNavKeys) {
        NavigationController.updatePrimaryScreens(bottomNavKeys)
    }

    val showBottomBar =
        currentScreen != LandingScreen &&
            currentScreen != AuthLoginScreen &&
            currentScreen != PairingCodeEntryScreen
    val gesturesEnabled = currentScreen in DRAWER_GESTURE_SCREENS
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // B7 (Jun 30 2026, kanban t_424): close drawer if gestures are disabled to dismiss scrim
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && drawerState.isOpen) {
            drawerState.snapTo(DrawerValue.Closed)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    val connectionStatus by HermesWsClient.connectionStatus.collectAsState()
                    val statusColor =
                        when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> LocalHermesStatusColors.current.success

                            ConnectionStatus.CONNECTING,
                            ConnectionStatus.RECONNECTING,
                            -> LocalHermesStatusColors.current.warning

                            ConnectionStatus.DISCONNECTED,
                            ConnectionStatus.NO_NETWORK,
                            ConnectionStatus.AUTH_EXPIRED,
                            -> LocalHermesStatusColors.current.error
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
                        ScreenRegistry.ALL_SCREENS
                            .filter { it.drawerSection == section }
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
