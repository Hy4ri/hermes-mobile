package com.m57.hermescontrol.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.m57.hermescontrol.R

/** System authentication bridge. Prompts only follow an explicit tap; cancellation never loops. */
internal class AppLockController(
    private val activity: ComponentActivity,
) {
    val session = ViewModelProvider(activity)[AppLockSession::class.java]
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private var biometricCancellation: CancellationSignal? = null
    private var biometricRequest: AppLockAuthRequest? = null
    private val credentialLauncher =
        activity.activityResultRegistry.register(
            "app_lock_device_credential",
            activity,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val request = session.credentialRequest
            session.credentialRequest = null
            if (request != null) complete(request, result.resultCode == Activity.RESULT_OK)
        }
    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    session.screenOff()
                    biometricCancellation?.cancel()
                }
            }
        }

    init {
        updateSecureWindow()
        ContextCompat.registerReceiver(
            activity,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun resume() {
        session.resume(SystemClock.elapsedRealtime(), keyguard.isDeviceSecure)
        updateSecureWindow()
    }

    fun pause() = session.pause()

    fun stop() = session.stop(SystemClock.elapsedRealtime(), activity.isChangingConfigurations)

    fun destroy() {
        activity.unregisterReceiver(screenOffReceiver)
        biometricRequest?.let { complete(it, false) }
        biometricCancellation?.cancel()
    }

    fun updateSecureWindow() {
        if (session.policy.enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun authenticate(action: AppLockAuthAction) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            session.credentialRequest != null
        ) {
            return
        }
        val request = session.beginAuthentication(action, keyguard.isDeviceSecure) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                authenticateModern(request)
            } else {
                // API 26–28: framework device-credential confirmation needs no FragmentActivity/library.
                @Suppress("DEPRECATION")
                val intent =
                    keyguard.createConfirmDeviceCredentialIntent(
                        activity.getString(R.string.app_lock_title),
                        activity.getString(R.string.app_lock_prompt),
                    )
                if (intent == null) {
                    complete(request, false)
                } else {
                    session.credentialRequest = request
                    credentialLauncher.launch(intent)
                }
            }
        } catch (_: Exception) {
            session.credentialRequest = null
            complete(request, false)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION")
    private fun authenticateModern(request: AppLockAuthRequest) {
        val builder =
            BiometricPrompt
                .Builder(activity)
                .setTitle(activity.getString(R.string.app_lock_title))
                .setSubtitle(activity.getString(R.string.app_lock_prompt))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        biometricRequest = request
        builder.build().authenticate(
            cancellation,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    complete(request, true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    complete(request, false)
                }
            },
        )
    }

    private fun complete(
        request: AppLockAuthRequest,
        success: Boolean,
    ) {
        session.completeAuthentication(request, success, keyguard.isDeviceSecure, SystemClock.elapsedRealtime())
        if (biometricRequest == request) {
            biometricRequest = null
            biometricCancellation = null
        }
        updateSecureWindow()
    }
}
