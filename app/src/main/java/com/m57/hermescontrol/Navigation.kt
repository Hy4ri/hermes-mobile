package com.m57.hermescontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.ui.navigation.CassySessionDrawer
import com.m57.hermescontrol.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen as AuthLoginScreenContent
import com.m57.hermescontrol.ui.landing.LandingScreen as LandingScreenContent
import com.m57.hermescontrol.ui.navigation.ToolsHubScreen as ToolsHubScreenContent
import com.m57.hermescontrol.ui.pairing.PairingCodeEntryScreen as PairingCodeEntryScreenContent

private val DRAWER_GESTURE_SCREENS: Set<NavKey> =
    ScreenRegistry.ALL_SCREENS.mapTo(mutableSetOf()) { it.key } + ToolsHubScreen

private fun appEntryProvider(
    sessionId: String?,
    openDrawer: () -> Unit,
) = entryProvider {
    entry<LandingScreen> {
        LandingScreenContent(
            onAuthLogin = { NavigationController.navigateTo(AuthLoginScreen) },
            onPairingLogin = { NavigationController.navigateTo(PairingCodeEntryScreen) },
        )
    }

    entry<AuthLoginScreen> {
        AuthLoginScreenContent(
            onConnected = { NavigationController.resetTo(ChatScreen) },
            onBack = { NavigationController.goBack() },
        )
    }

    entry<PairingCodeEntryScreen> {
        PairingCodeEntryScreenContent(
            onConnected = { NavigationController.resetTo(ChatScreen) },
            onBack = { NavigationController.goBack() },
        )
    }

    entry<ToolsHubScreen> {
        ToolsHubScreenContent(
            onOpenDrawer = openDrawer,
            onNavigate = { definition -> NavigationController.navigateTo(definition.key) },
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
    val startScreen: NavKey = if (!token.isNullOrBlank()) ChatScreen else LandingScreen
    val backStack = remember(startScreen) { NavBackStack(startScreen) }
    NavigationController.backStack = backStack

    val currentScreen = backStack.lastOrNull() ?: startScreen
    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val workspaceViewModel: WorkspaceViewModel = viewModel()
    val workspaceState by workspaceViewModel.uiState.collectAsStateWithLifecycle()
    val connectionStatus by HermesWsClient.connectionStatus.collectAsState()

    val gesturesEnabled = currentScreen in DRAWER_GESTURE_SCREENS
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    LaunchedEffect(Unit) {
        NavigationController.updatePrimaryScreens(setOf(ChatScreen, ToolsHubScreen, SettingsScreen))
    }
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && drawerState.isOpen) drawerState.snapTo(DrawerValue.Closed)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet {
                    CassySessionDrawer(
                        state = workspaceState,
                        connectionStatus = connectionStatus,
                        onNewChat = {
                            closeDrawer()
                            NavigationController.pendingNewChat = true
                            NavigationController.resetTo(ChatScreen)
                        },
                        onSessionSelected = { selectedId ->
                            workspaceViewModel.openSession(selectedId)
                            NavigationController.pendingSessionId = selectedId
                            closeDrawer()
                            NavigationController.resetTo(ChatScreen)
                        },
                        onToggleSessionPin = workspaceViewModel::togglePin,
                        onManageSessions = {
                            closeDrawer()
                            NavigationController.navigateTo(HistoryScreen)
                        },
                        onOpenTools = {
                            closeDrawer()
                            NavigationController.navigateTo(ToolsHubScreen)
                        },
                        onOpenSettings = {
                            closeDrawer()
                            NavigationController.navigateTo(SettingsScreen)
                        },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { NavigationController.goBack() },
            entryProvider = appEntryProvider(sessionId, openDrawer),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
