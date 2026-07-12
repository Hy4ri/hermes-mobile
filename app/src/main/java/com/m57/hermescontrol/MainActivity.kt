package com.m57.hermescontrol

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.security.AppLockPolicy
import com.m57.hermescontrol.security.AppLockScreen
import com.m57.hermescontrol.theme.HermesControlTheme

class MainActivity : FragmentActivity() {
    private var notificationSessionId by mutableStateOf<String?>(null)
    private var isUnlocked by mutableStateOf(false)
    private var lockError by mutableStateOf<String?>(null)
    private var authInFlight = false
    private var lastBackgroundAtMs: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        notificationSessionId = intent?.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)

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
                    if (isUnlocked) {
                        MainNavigation(sessionId = notificationSessionId)
                    } else {
                        AppLockScreen(errorMessage = lockError, onUnlock = ::authenticate)
                        LaunchedEffect(Unit) { authenticate() }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val backgroundAt = lastBackgroundAtMs
        if (isUnlocked && backgroundAt != null &&
            AppLockPolicy.shouldLock(backgroundAt, SystemClock.elapsedRealtime())
        ) {
            isUnlocked = false
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !authInFlight) {
            lastBackgroundAtMs = SystemClock.elapsedRealtime()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationSessionId = intent.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)
    }

    private fun authenticate() {
        if (authInFlight || isUnlocked) return
        val authenticators =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            }
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            lockError =
                when (availability) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                        "Set up a screen lock or fingerprint in HyperOS to unlock Cassy."
                    }

                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                        "This device does not provide a secure biometric authenticator."
                    }

                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                        "Device authentication is temporarily unavailable."
                    }

                    else -> {
                        "Secure device authentication is unavailable (code $availability)."
                    }
                }
            return
        }

        lockError = null
        authInFlight = true
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authInFlight = false
                        lastBackgroundAtMs = null
                        isUnlocked = true
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        authInFlight = false
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            lockError = errString.toString()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        lockError = "Authentication did not match. Try again."
                    }
                },
            )
        val promptBuilder =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock Cassy Control")
                .setSubtitle("Authenticate to access Hermes sessions and administration")
                .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            promptBuilder.setNegativeButtonText("Cancel")
        }
        prompt.authenticate(promptBuilder.build())
    }
}
