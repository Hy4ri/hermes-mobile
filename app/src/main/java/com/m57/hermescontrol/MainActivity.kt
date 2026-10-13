package com.m57.hermescontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.requiresLocalNetworkPermission
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.authlogin.AuthLoginScreen
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.util.LocaleContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** New logins request permission in AuthLoginScreen; saved token and ticket modes gate startup. */
internal fun needsSavedSessionLanGate(
    sdkInt: Int,
    token: String?,
    gatedMode: Boolean,
    url: String,
): Boolean = (gatedMode || !token.isNullOrBlank()) && requiresLocalNetworkPermission(sdkInt, url)

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

    private var baseContextLanguageCode: String = LocaleContextWrapper.SYSTEM_LANGUAGE
    private var startupLanState by mutableStateOf(StartupLanState.Checking)

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
                AuthManager.initializationState.collectLatest { state ->
                    if (state == AuthManager.InitializationState.Ready) {
                        if (AuthManager.isGatedMode() || !AuthManager.getToken().isNullOrBlank()) {
                            // Navigation and its ViewModels must be admitted before dialing.
                            awaitStartupLanAdmission(snapshotFlow { startupLanState })
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
                                // Capture the selected, resolved endpoint only at startup. A
                                // subsequent new login is handled by AuthLoginScreen.
                                val savedToken = remember { AuthManager.getToken() }
                                val savedGatedMode = remember { AuthManager.isGatedMode() }
                                val savedUrl = remember { AuthManager.getBaseUrl() }
                                SavedSessionLanGate(savedToken, savedGatedMode, savedUrl)
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

    @Composable
    private fun SavedSessionLanGate(
        token: String?,
        gatedMode: Boolean,
        url: String,
    ) {
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                startupLanState = startupLanState.next(StartupLanEvent.PermissionResult(granted))
            }
        LaunchedEffect(token, gatedMode, url) {
            val required =
                withContext(Dispatchers.IO) {
                    needsSavedSessionLanGate(Build.VERSION.SDK_INT, token, gatedMode, url)
                }
            val grantedOrNotRequired =
                !required || ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED
            startupLanState = startupLanState.next(StartupLanEvent.PermissionChecked(grantedOrNotRequired))
        }
        // Settings may grant access while paused. Ordinary resumes must not
        // remove already-admitted navigation and recreate the chat ViewModel.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (startupLanState == StartupLanState.Denied &&
                Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startupLanState = startupLanState.next(StartupLanEvent.Resume(granted = true))
            }
        }
        LaunchedEffect(startupLanState) {
            if (startupLanState == StartupLanState.Request) {
                launcher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        when (startupLanState) {
            StartupLanState.ChooseConnection -> {
                AuthLoginScreen(
                    onConnected = { startupLanState = startupLanState.next(StartupLanEvent.Connected) },
                    onBack = { startupLanState = startupLanState.next(StartupLanEvent.Back) },
                )
            }

            StartupLanState.Ready -> {
                MainNavigation()
            }

            StartupLanState.Checking, StartupLanState.Request -> {
                LoadingState()
            }

            StartupLanState.Denied -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.connect_error_lan_permission_denied))
                    Button(onClick = { startupLanState = startupLanState.next(StartupLanEvent.ChooseConnection) }) {
                        Text(stringResource(R.string.auth_login_title))
                    }
                    Button(onClick = { startupLanState = startupLanState.next(StartupLanEvent.Retry) }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    Button(onClick = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            },
                        )
                    }) {
                        Text(stringResource(R.string.settings_about_update_open_settings))
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
        lifecycleScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            UpdateNoticeManager.checkOnLaunch()
        }
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
