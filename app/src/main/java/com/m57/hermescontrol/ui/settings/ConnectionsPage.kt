package com.m57.hermescontrol.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager

/** Reuse the connection settings flow, including before the first server login. */
@Composable
internal fun ConnectionsPage() {
    val token by AuthManager.tokenFlow.collectAsStateWithLifecycle()
    SettingsConnectionPage(
        onBack = { NavigationController.goBack() },
        onLogout = {},
        showLogout = !token.isNullOrBlank(),
    )
}
