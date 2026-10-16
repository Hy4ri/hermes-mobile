package com.m57.hermescontrol.shortcuts

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient

@Composable
internal fun LauncherShortcutNavigationEffect(hasToken: Boolean) {
    val request = LauncherShortcuts.pendingRequest
    val scope by AuthManager.dataScopeFlow.collectAsState()
    val status by HermesWsClient.connectionStatus.collectAsState()
    val context = LocalContext.current
    val unavailable = stringResource(R.string.shortcut_unavailable)
    LaunchedEffect(request, hasToken, scope, status) {
        request ?: return@LaunchedEffect
        // Static actions can survive login. A session target must never survive logout into another account.
        if (!hasToken) {
            if (request.action == LauncherShortcuts.ACTION_SESSION) LauncherShortcuts.consume(request)
            return@LaunchedEffect
        }
        when (request.action) {
            LauncherShortcuts.ACTION_NEW_CHAT -> {
                NavigationController.openNewChat()
            }

            LauncherShortcuts.ACTION_HISTORY -> {
                NavigationController.consumePendingChatNavigation()
                NavigationController.consumePendingNewChatNavigation()
                NavigationController.navigateTo(HistoryScreen)
            }

            LauncherShortcuts.ACTION_SESSION -> {
                val owner = LauncherShortcuts.authenticatedScope()
                val matchesScope = owner != null && shortcutScopeId(owner) == request.scopeId
                if (matchesScope && status != ConnectionStatus.CONNECTED) return@LaunchedEffect
                val valid = owner != null && matchesScope && LauncherShortcuts.resolve(request, owner)
                if (LauncherShortcuts.pendingRequest != request) return@LaunchedEffect
                if (valid && LauncherShortcuts.authenticatedScope() == owner) {
                    NavigationController.openChatSessionFromShortcut(requireNotNull(request.sessionId), owner)
                } else {
                    NavigationController.navigateTo(HistoryScreen)
                    Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                    ShortcutRefreshEvents.refresh()
                }
            }
        }
        LauncherShortcuts.consume(request)
    }
}
