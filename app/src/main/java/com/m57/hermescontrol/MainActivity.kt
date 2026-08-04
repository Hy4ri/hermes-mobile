package com.m57.hermescontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.util.LocaleContextWrapper

class MainActivity : ComponentActivity() {
    /**
     * Apply the user-selected display language before any view is inflated.
     * Reads the persisted code from [AuthManager]; an uninitialized store
     * (shouldn't happen here, but guarded) falls back to the device locale.
     */
    override fun attachBaseContext(base: Context) {
        val code =
            runCatching { AuthManager.getAppLanguage() }
                .getOrDefault(LocaleContextWrapper.SYSTEM_LANGUAGE)
        super.attachBaseContext(LocaleContextWrapper.wrapWithCode(base, code))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        consumeNotificationIntent(intent)

        enableEdgeToEdge()
        setContent {
            val themePreference by AuthManager.themePreferenceFlow.collectAsState()
            val useDynamicColors by AuthManager.useDynamicColorsFlow.collectAsState()
            val themePreset by AuthManager.themePresetFlow.collectAsState()
            HermesControlTheme(
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        intent?.removeExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        sessionId?.takeIf { it.isNotBlank() }?.let(NavigationController::openChatSession)
    }
}
