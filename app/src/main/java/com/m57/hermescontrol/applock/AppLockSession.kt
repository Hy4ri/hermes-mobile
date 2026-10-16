package com.m57.hermescontrol.applock

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.m57.hermescontrol.R

internal enum class AppLockAuthAction { UNLOCK, ENABLE, DISABLE }

internal data class AppLockAuthRequest(
    val action: AppLockAuthAction,
    val generation: Long,
    val id: Long,
)

/** Activity-retained authorization; a new process starts locked regardless of saved-instance state. */
internal class AppLockSession(
    application: Application,
) : AndroidViewModel(application) {
    private val store = AppLockStore(application)
    private val initial = store.read()
    var policy by mutableStateOf(AppLockPolicy().configure(initial.enabled, initial.timeoutMs))
        private set
    var hasOpened by mutableStateOf(!initial.enabled)
        private set
    var pendingAuth by mutableStateOf<AppLockAuthRequest?>(null)
        private set
    var credentialRequest by mutableStateOf<AppLockAuthRequest?>(null)
    var error by mutableStateOf<Int?>(null)
        private set
    var deviceSecure by mutableStateOf(false)
        private set
    private var resumed = false
    private var nextRequestId = 0L

    fun resume(
        now: Long,
        secure: Boolean,
    ) {
        resumed = true
        deviceSecure = secure
        if (!secure && policy.enabled) {
            pendingAuth = null
            policy = policy.screenOff()
        }
        if (pendingAuth == null) policy = policy.foreground(now, secure)
        if (!policy.blocked) hasOpened = true
    }

    fun pause() {
        resumed = false
        policy = policy.cover()
    }

    fun stop(
        now: Long,
        changingConfigurations: Boolean,
    ) {
        if (changingConfigurations) return
        policy =
            if (pendingAuth != null && policy.enabled) {
                // Count time away if authentication is cancelled, but don't expire its own success ticket.
                policy.copy(covered = true, backgroundAt = policy.backgroundAt ?: now)
            } else {
                policy.background(now)
            }
    }

    fun screenOff() {
        pendingAuth = null
        policy = policy.screenOff()
    }

    fun beginAuthentication(
        action: AppLockAuthAction,
        secure: Boolean,
    ): AppLockAuthRequest? {
        deviceSecure = secure
        if (!resumed || pendingAuth != null) return null
        if (!secure) {
            error = R.string.app_lock_unavailable
            return null
        }
        error = null
        return AppLockAuthRequest(action, policy.generation, ++nextRequestId).also { pendingAuth = it }
    }

    fun completeAuthentication(
        request: AppLockAuthRequest,
        success: Boolean,
        secure: Boolean,
        now: Long,
    ) {
        if (pendingAuth != request || request.generation != policy.generation) return
        pendingAuth = null
        deviceSecure = secure
        if (!success || !secure) {
            error = if (secure) R.string.app_lock_auth_cancelled else R.string.app_lock_unavailable
            if (resumed) policy = policy.foreground(now, secure)
            return
        }
        val enabled =
            when (request.action) {
                AppLockAuthAction.ENABLE -> true
                AppLockAuthAction.DISABLE -> false
                AppLockAuthAction.UNLOCK -> policy.enabled
            }
        if (request.action != AppLockAuthAction.UNLOCK && !store.save(AppLockSettings(enabled, policy.timeoutMs))) {
            error = R.string.app_lock_storage_error
            // A failed policy write is never acknowledged as an unlocked/disabled state.
            policy = policy.screenOff()
            return
        }
        policy = policy.configure(enabled, policy.timeoutMs).let { it.authenticated(it.generation) }
        if (resumed) policy = policy.foreground(now, secure)
        if (!policy.blocked) hasOpened = true
    }

    fun changeTimeout(timeoutMs: Long) {
        if (policy.blocked || pendingAuth != null || timeoutMs !in AppLockPolicy.TIMEOUTS) return
        if (store.save(AppLockSettings(policy.enabled, timeoutMs))) {
            policy = policy.configure(policy.enabled, timeoutMs)
            error = null
        } else {
            error = R.string.app_lock_storage_error
        }
    }
}
