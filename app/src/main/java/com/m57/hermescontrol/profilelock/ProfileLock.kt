package com.m57.hermescontrol.profilelock

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.ProjectsScreen
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsLanguage
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Observable lock state. [target] is null while locked only when the stored policy is unreadable. */
data class ProfileLockState(
    val locked: Boolean = false,
    val target: ProfileLockTarget? = null,
)

sealed interface ProfileUnlockResult {
    data object Unlocked : ProfileUnlockResult

    data class WrongPin(
        val attemptsLeft: Int,
    ) : ProfileUnlockResult

    data class CoolingDown(
        val retryAtMs: Long,
    ) : ProfileUnlockResult

    /** The policy could not be read or written; the lock stays on. */
    data object Failed : ProfileUnlockResult
}

/**
 * Local, PIN-protected "Lock to this profile" restriction (#1643).
 *
 * While locked the app keeps Chat, History, Projects and local Settings pages only. Navigation,
 * connection/profile selection and global config writes are refused at their mutation points. This is a
 * device-local restriction, not server authorization: the profile's agent tools need separate limits.
 */
object ProfileLock {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L
    private const val HASH_ITERATIONS = 120_000
    private const val HASH_BITS = 256

    /** Destinations that stay reachable while locked. Everything else is protected. */
    private val allowedWhileLocked: Set<NavKey> =
        setOf(
            LandingScreen,
            ChatScreen,
            HistoryScreen,
            ProjectsScreen,
            SettingsScreen,
            SettingsAppearance,
            SettingsLanguage,
            SettingsChat,
            SettingsBehavior,
            SettingsAbout,
        )

    private var store: ProfileLockStore? = null
    private val lock = Any()

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(ProfileLockState())
    val state: StateFlow<ProfileLockState> = _state.asStateFlow()

    val isLocked: Boolean get() = _state.value.locked

    fun init(context: Context) {
        val created = runCatching { ProfileLockStore(context.applicationContext) }.getOrNull()
        synchronized(lock) {
            store = created
            // No store means no lock was ever readable or writable here; enable() then refuses.
            publish(created?.read() ?: ProfileLockPolicy())
        }
    }

    @VisibleForTesting
    internal fun setStoreForTest(testStore: ProfileLockStore?) {
        synchronized(lock) {
            store = testStore
            publish(testStore?.read() ?: ProfileLockPolicy())
        }
    }

    fun isValidPin(pin: String): Boolean = pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

    /** Locks the app to [target]. Hashes the PIN, so call it off the main thread. */
    fun enable(
        target: ProfileLockTarget,
        pin: String,
    ): Boolean {
        if (target.profileName.isBlank() || !isValidPin(pin)) return false
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val policy =
            ProfileLockPolicy(
                enabled = true,
                target = target,
                pinHash = encode(hash(pin, salt)),
                pinSalt = encode(salt),
            )
        synchronized(lock) {
            val current = store ?: return false
            if (current.read().enabled) return false
            if (!current.save(policy)) return false
            publish(policy)
            return true
        }
    }

    /** Turns the lock off when [pin] is correct. Hashes the PIN, so call it off the main thread. */
    fun disable(pin: String): ProfileUnlockResult =
        synchronized(lock) {
            val current = store ?: return ProfileUnlockResult.Failed
            val policy = current.read()
            if (!policy.enabled) {
                publish(policy)
                return ProfileUnlockResult.Unlocked
            }
            if (!policy.canVerify) return ProfileUnlockResult.Failed
            val now = clock()
            if (policy.lockoutUntilMs > now) return ProfileUnlockResult.CoolingDown(policy.lockoutUntilMs)

            val salt = decode(policy.pinSalt) ?: return ProfileUnlockResult.Failed
            val expected = decode(policy.pinHash) ?: return ProfileUnlockResult.Failed
            if (MessageDigest.isEqual(expected, hash(pin, salt))) {
                if (!current.save(ProfileLockPolicy())) return ProfileUnlockResult.Failed
                publish(ProfileLockPolicy())
                return ProfileUnlockResult.Unlocked
            }

            val failures = policy.failedAttempts + 1
            val next =
                if (failures >= MAX_ATTEMPTS) {
                    policy.copy(failedAttempts = 0, lockoutUntilMs = now + LOCKOUT_MS)
                } else {
                    policy.copy(failedAttempts = failures, lockoutUntilMs = 0L)
                }
            if (!current.save(next)) return ProfileUnlockResult.Failed
            publish(next)
            return if (next.lockoutUntilMs > now) {
                ProfileUnlockResult.CoolingDown(next.lockoutUntilMs)
            } else {
                ProfileUnlockResult.WrongPin(MAX_ATTEMPTS - failures)
            }
        }

    /** Whether [key] may be shown or navigated to now. */
    fun allows(key: NavKey): Boolean = !isLocked || key in allowedWhileLocked

    /** Whether the active server-side profile may become [name]. */
    fun allowsActiveProfile(name: String?): Boolean {
        val current = _state.value
        return !current.locked || (name != null && current.target?.profileName == name)
    }

    /** Whether the selected local connection profile may become [connectionProfileId]. */
    fun allowsConnection(connectionProfileId: String?): Boolean {
        val current = _state.value
        val target = current.target
        return !current.locked ||
            (target != null && target.connectionProfileId == connectionProfileId?.takeIf { it.isNotBlank() })
    }

    /** Session-scoped changes stay available; persistent (global) config writes need the owner. */
    fun allowsRpc(
        method: String,
        params: Map<String, Any>,
    ): Boolean = !isLocked || method != WsMethods.CONFIG_SET || params["scope"]?.toString() != "global"

    /**
     * Slash commands that stay available while locked. Everything else (e.g. /config, /tools, /skills,
     * /cron, /profile, /yolo, /update, /model --global) can change the agent or server, so it needs the owner.
     */
    private val slashAllowedWhileLocked =
        setOf(
            "/help",
            "/commands",
            "/status",
            "/usage",
            "/context",
            "/whoami",
            "/version",
            "/insights",
            "/title",
            "/retry",
            "/undo",
            "/compress",
            "/compact",
            "/btw",
            "/steer",
            "/queue",
            "/q",
            "/stop",
            "/interrupt",
            "/new",
            "/start",
            "/resume",
            "/history",
            "/fork",
            "/branch",
            "/model",
            "/reasoning",
            "/fast",
        )
    private val globalFlags = setOf("--global", "-g", "global")

    /** Whether the typed slash [command] may run now. */
    fun allowsSlashCommand(command: String): Boolean {
        if (!isLocked) return true
        val tokens = command.trim().split(Regex("\\s+"))
        val name = tokens.first().lowercase()
        if (name !in slashAllowedWhileLocked) return false
        // Session-scoped model/reasoning/fast changes stay; persistent ones need the owner.
        return tokens.drop(1).none { it.lowercase() in globalFlags }
    }

    private fun publish(policy: ProfileLockPolicy) {
        _state.value = ProfileLockState(locked = policy.enabled, target = policy.target.takeIf { policy.enabled })
    }

    private fun hash(
        pin: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?): ByteArray? =
        value?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }
}
