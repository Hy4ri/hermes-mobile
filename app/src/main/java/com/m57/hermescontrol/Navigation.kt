package com.m57.hermescontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.DisableDrawerGestures
import com.m57.hermescontrol.ui.common.DrawerGestureController
import com.m57.hermescontrol.ui.common.LocalDrawerGestureController
import com.m57.hermescontrol.ui.settings.SettingsAboutPage
import com.m57.hermescontrol.ui.settings.SettingsAppearancePage
import com.m57.hermescontrol.ui.settings.SettingsBehaviorPage
import com.m57.hermescontrol.ui.settings.SettingsChatPage
import com.m57.hermescontrol.ui.settings.SettingsConnectionPage
import com.m57.hermescontrol.ui.settings.SettingsNavBarPage
import com.m57.hermescontrol.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent
import com.m57.hermescontrol.ui.pairing.PairingCodeEntryScreen as PairingCodeEntryScreenContent

private val settingsDestinations: Set<NavKey> =
    setOf(
        SettingsScreen,
        SettingsConnection,
        SettingsAppearance,
        SettingsChat,
        SettingsNavBar,
        SettingsBehavior,
        SettingsAbout,
    )

/**
 * Keep Settings highlighted in the bottom nav while one of its drill-down
 * sub-pages is visible. Navigation is unaffected — this only maps the
 * *selection* key back to the Settings parent so the indicator doesn't drop.
 */
internal fun selectedBottomNavDestination(currentScreen: NavKey): NavKey =
    if (currentScreen in settingsDestinations) SettingsScreen else currentScreen

/** Maps a compact bottom-nav display mode to its bar height. */
internal fun Modifier.bottomNavigationHeight(displayMode: BottomNavDisplayMode): Modifier =
    when (displayMode) {
        BottomNavDisplayMode.ICON_ONLY -> height(56.dp)
        BottomNavDisplayMode.TEXT_ONLY -> height(44.dp)
        BottomNavDisplayMode.ICON_AND_TEXT -> heightIn(min = 80.dp)
    }

private fun resolveBottomNavItems(names: List<String>): List<ScreenDefinition> =
    names.mapNotNull { name -> ScreenRegistry.ALL_SCREENS.firstOrNull { it.key::class.simpleName == name } }

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
        // Landing doesn't use HermesScaffold — opt out of drawer gestures explicitly (issue #619).
        DisableDrawerGestures()
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
        DisableDrawerGestures()
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
        DisableDrawerGestures()
    }

    ScreenRegistry.ALL_SCREENS.forEach { screen ->
        addEntryProvider(clazz = screen.key::class) {
            screen.content(sessionId, openDrawer)
        }
    }

    // ── Settings drill-down sub-pages ───────────────────────────────────
    // Each passes drawerGesturesEnabled = false to HermesScaffold — this is the
    // single source of truth that prevents the drawer-scrim stuck-open bug
    // (issue #619). No global gesture set, no closeDrawer callback, no
    // LaunchedEffect(snapTo(Closed)) — the scaffold reconciles automatically.
    entry<SettingsConnection> {
        SettingsConnectionPage(
            onBack = { NavigationController.goBack() },
            onLogout = { /* handled by caller via goBack fallback */ },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsAppearance> {
        SettingsAppearancePage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsChat> {
        SettingsChatPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsNavBar> {
        SettingsNavBarPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsBehavior> {
        SettingsBehaviorPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
    }
    entry<SettingsAbout> {
        SettingsAboutPage(
            onBack = { NavigationController.goBack() },
            viewModel = viewModel { SettingsViewModel() },
        )
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
    val selectedDestination = selectedBottomNavDestination(currentScreen)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Single source of truth for drawer gestures (issue #619).
    // HermesScaffold / DisableDrawerGestures reconcile each screen's preference
    // into this controller via SideEffect; ModalNavigationDrawer reads .enabled
    // below. No DRAWER_GESTURE_SCREENS set, no LaunchedEffect(snapTo(Closed)),
    // no closeDrawer callback — the controller closes the drawer itself when a
    // screen opts out.
    val gestureController =
        remember(drawerState, scope) {
            DrawerGestureController(drawerState, scope)
        }

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

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    CompositionLocalProvider(LocalDrawerGestureController provides gestureController) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gestureController.enabled,
            drawerContent = {
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
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.navigationBars,
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar(
                            modifier = Modifier.bottomNavigationHeight(bottomNavDisplayMode),
                        ) {
                            bottomNavItems.forEach { item ->
                                val showIcon =
                                    bottomNavDisplayMode == BottomNavDisplayMode.ICON_AND_TEXT ||
                                        bottomNavDisplayMode == BottomNavDisplayMode.ICON_ONLY
                                val showLabel =
                                    bottomNavDisplayMode == BottomNavDisplayMode.ICON_AND_TEXT ||
                                        bottomNavDisplayMode == BottomNavDisplayMode.TEXT_ONLY

                                val isSelected = selectedDestination == item.key

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
                                            "nav_${item.key::class.simpleName?.lowercase()?.removeSuffix(
                                                "screen",
                                            ) ?: ""}",
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
}
