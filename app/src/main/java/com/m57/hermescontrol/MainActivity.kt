package com.m57.hermescontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.util.LocaleContextWrapper
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
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

    private var baseContextLanguageCode: String = LocaleContextWrapper.SYSTEM_LANGUAGE

    /**
     * Apply the user-selected display language before any view is inflated.
     * Reads the persisted code from [AuthManager]; an uninitialized store
     * (shouldn't happen here, but guarded) falls back to the device locale.
     */
    override fun attachBaseContext(base: Context) {
        val code =
            runCatching { AuthManager.getAppLanguage() }
                .getOrDefault(LocaleContextWrapper.SYSTEM_LANGUAGE)
        baseContextLanguageCode = code
        super.attachBaseContext(LocaleContextWrapper.wrapWithCode(base, code))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        consumeNotificationIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AuthManager.initializationState.collect { state ->
                    if (state == AuthManager.InitializationState.Ready) {
                        if (AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()) {
                            HermesWsClient.connect()
                        }
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            val themePreference by AuthManager.themePreferenceFlow.collectAsState()
            val useDynamicColors by AuthManager.useDynamicColorsFlow.collectAsState()
            val themePreset by AuthManager.themePresetFlow.collectAsState()
            val chatFontScale by AuthManager.chatFontScaleFlow.collectAsState()
            HermesControlTheme(
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
                chatFontScale = chatFontScale,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val initState by AuthManager.initializationState.collectAsStateWithLifecycle()
                    when (initState) {
                        AuthManager.InitializationState.Loading -> {
                            LoadingState()
                        }

                        AuthManager.InitializationState.Ready -> {
                            val savedLanguage = AuthManager.getAppLanguage()
                            if (savedLanguage != baseContextLanguageCode) {
                                // The next activity attaches with loaded preferences. Keep navigation
                                // gated across recompositions until that recreation completes (#1171).
                                LaunchedEffect(savedLanguage) {
                                    recreate()
                                }
                                LoadingState()
                            } else {
                                MainNavigation()
                            }
                        }

                        AuthManager.InitializationState.Failed -> {
                            ErrorState(
                                message = stringResource(R.string.auth_init_failed),
                                onRetry = {
                                    AuthManager.init(applicationContext)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.setAppForeground(this, true)
        ExternalActivityLifecycleGuard.onHostResumed()
        NotificationHelper.stop(this)
    }

    override fun onPause() {
        ExternalActivityLifecycleGuard.onHostPaused()
        // Prepare the FGS while still eligible to start it. A pause alone does
        // not mean background: rotation also pauses and recreates this activity.
        NotificationHelper.start(this)
        super.onPause()
    }

    override fun onStop() {
        // isChangingConfigurations is reliable here, not during onPause.
        // Keep the shared socket alive across recreation, but still release an
        // idle connection when the user really leaves the app or locks the phone.
        if (!isChangingConfigurations) NotificationHelper.setAppForeground(this, false)
        super.onStop()
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        // Issue #832: MainActivity is exported (launcher requirement) — only
        // honor intents stamped with our own notification action.
        if (intent?.action != ACTION_OPEN_CHAT_FROM_NOTIFICATION) return
        val sessionId = intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        intent.removeExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
        sessionId?.takeIf { it.isNotBlank() }?.let(NavigationController::openChatSessionFromNotification)
    }
}
