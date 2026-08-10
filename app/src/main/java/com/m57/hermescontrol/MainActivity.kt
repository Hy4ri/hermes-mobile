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
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.util.LocaleContextWrapper

class MainActivity : ComponentActivity() {
    companion object {
        /**
         * Action that identifies notification-driven "open this chat" intents.
         * [ChatNotificationService] stamps it on the content intent; the
         * consumer refuses anything else (issue #832 — MainActivity is
         * exported as the launcher, so a foreign app could otherwise inject
         * a session id via an explicit intent).
         */
        const val ACTION_OPEN_CHAT_FROM_NOTIFICATION =
            "com.m57.hermescontrol.ACTION_OPEN_CHAT_FROM_NOTIFICATION"
    }

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

    override fun onStart() {
        super.onStart()
        NotificationHelper.setAppForeground(this, true)
        NotificationHelper.stop(this)
        if (AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()) {
            HermesWsClient.connect()
        }
    }

    override fun onStop() {
        NotificationHelper.setAppForeground(this, false)
        NotificationHelper.start(this)
        super.onStop()
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        // Issue #832: MainActivity is exported (launcher requirement) — only
        // honor intents stamped with our own notification action.
        if (intent?.action != ACTION_OPEN_CHAT_FROM_NOTIFICATION) return
        val sessionId = intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        intent.removeExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        sessionId?.takeIf { it.isNotBlank() }?.let(NavigationController::openChatSession)
    }
}
