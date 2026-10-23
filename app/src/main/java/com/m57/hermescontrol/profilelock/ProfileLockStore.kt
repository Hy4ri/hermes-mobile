package com.m57.hermescontrol.profilelock

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** The server-side Hermes profile a device is locked to, inside one local connection profile (#1643). */
data class ProfileLockTarget(
    /** Local connection profile id ([com.m57.hermescontrol.data.local.AuthManager.getSelectedProfileId]); null = default. */
    val connectionProfileId: String?,
    /** Server-side Hermes profile name. */
    val profileName: String,
)

internal data class ProfileLockPolicy(
    val enabled: Boolean = false,
    /** Null while [enabled] means the stored policy is unreadable: stay restricted, never unlock. */
    val target: ProfileLockTarget? = null,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val failedAttempts: Int = 0,
    val lockoutUntilMs: Long = 0L,
) {
    val canVerify: Boolean get() = enabled && target != null && !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()

    companion object {
        /** Restricted policy that no PIN can unlock; recovery is clearing the app data. */
        val UNREADABLE = ProfileLockPolicy(enabled = true)
    }
}

/**
 * Security policy store for the profile lock. Like [com.m57.hermescontrol.applock.AppLockStore], it is
 * separate from best-effort dashboard preferences: read errors fail closed and a checked, synchronous
 * commit precedes any UI acknowledgement. Only a salted PIN hash is stored, never the PIN.
 */
internal class ProfileLockStore(
    private val prefs: SharedPreferences,
    private val previouslyStored: Boolean = false,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").exists() ||
            File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml.bak").exists(),
    )

    fun read(): ProfileLockPolicy =
        try {
            // Android can turn malformed preference XML into an empty map without throwing.
            if (previouslyStored && !prefs.contains(KEY_ENABLED)) {
                ProfileLockPolicy.UNREADABLE
            } else if (!prefs.getBoolean(KEY_ENABLED, false)) {
                ProfileLockPolicy()
            } else {
                val profile = prefs.getString(KEY_PROFILE, null)
                val hash = prefs.getString(KEY_PIN_HASH, null)
                val salt = prefs.getString(KEY_PIN_SALT, null)
                if (profile.isNullOrBlank() || hash.isNullOrBlank() || salt.isNullOrBlank()) {
                    ProfileLockPolicy.UNREADABLE
                } else {
                    ProfileLockPolicy(
                        enabled = true,
                        target =
                            ProfileLockTarget(
                                prefs.getString(KEY_CONNECTION, null)?.takeIf { it.isNotBlank() },
                                profile,
                            ),
                        pinHash = hash,
                        pinSalt = salt,
                        failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0).coerceAtLeast(0),
                        lockoutUntilMs = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L),
                    )
                }
            }
        } catch (_: Exception) {
            ProfileLockPolicy.UNREADABLE
        }

    fun save(policy: ProfileLockPolicy): Boolean {
        val previous = read()
        val committed = runCatching { commit(policy) }.getOrDefault(false)
        if (!committed) {
            // commit(false) still changes SharedPreferences' in-memory map; restore it so a failed
            // disable is never observed as a successful policy change.
            runCatching { commit(previous) }
        }
        return committed
    }

    private fun commit(policy: ProfileLockPolicy): Boolean {
        val editor = prefs.edit().clear().putBoolean(KEY_ENABLED, policy.enabled)
        val target = policy.target
        if (policy.enabled && target != null) {
            target.connectionProfileId?.let { editor.putString(KEY_CONNECTION, it) }
            editor
                .putString(KEY_PROFILE, target.profileName)
                .putString(KEY_PIN_HASH, policy.pinHash)
                .putString(KEY_PIN_SALT, policy.pinSalt)
                .putInt(KEY_FAILED_ATTEMPTS, policy.failedAttempts)
                .putLong(KEY_LOCKOUT_UNTIL, policy.lockoutUntilMs)
        }
        return editor.commit()
    }

    companion object {
        private const val FILE_NAME = "profile_lock_policy"
        internal const val KEY_ENABLED = "enabled"
        internal const val KEY_CONNECTION = "connection_profile_id"
        internal const val KEY_PROFILE = "profile_name"
        internal const val KEY_PIN_HASH = "pin_hash"
        internal const val KEY_PIN_SALT = "pin_salt"
        internal const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        internal const val KEY_LOCKOUT_UNTIL = "lockout_until_ms"
    }
}
