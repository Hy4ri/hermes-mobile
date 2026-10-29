package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie

/**
 * Persistence contract for server-scoped cookies.
 *
 * Implementations store cookies per logical server id (not the raw host) so
 * that switching [PersistentCookieJar] scopes never lets cookies from one
 * gateway leak into another (issue #470 — multi-server isolation).
 *
 * Splitting this into an interface lets unit tests inject an in-memory fake
 * ([com.m57.hermescontrol.data.remote.FakeEncryptedCookieStore]) instead of
 * spinning up [EncryptedSharedPreferences], which requires Android Keystore.
 */
interface CookieStore {
    /** Persist [cookies] for [serverId]. Replaces the previous set atomically. */
    suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    )

    /** Load the persisted cookie set for [serverId] (may be empty). */
    suspend fun load(serverId: String): List<Cookie>

    /** Drop all persisted cookies for [serverId] (e.g. on logout). */
    suspend fun clear(serverId: String)

    /** Drop everything across all servers. */
    suspend fun clearAll()
}

/**
 * Encrypted, per-server cookie persistence backed by
 * [EncryptedSharedPreferences] (AES256-GCM). Cookies are serialized with
 * kotlinx.serialization and written on [Dispatchers.IO].
 *
 * Legacy migration: the pre-#470 code stored a single raw
 * `hermes_session_at` value in `AuthManager`'s prefs under
 * [LEGACY_SESSION_COOKIE_KEY]. [load] transparently folds any such legacy
 * value into the requested [serverId] scope on first read so existing gated
 * sessions keep working without a re-login. The encrypted copy is committed
 * before the legacy source is removed.
 */
class EncryptedCookieStore(
    private val context: Context,
    private val legacyPrefsDeferred: Deferred<SharedPreferences>? = null,
) : CookieStore {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val masterKeyAlias by lazy { MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC) }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    ) = withContext(Dispatchers.IO) {
        val serialized = cookies.map { it.serialize() }
        check(prefs.edit().putString(keyFor(serverId), json.encodeToString(serialized)).commit()) {
            "Unable to persist encrypted cookies"
        }
    }

    override suspend fun load(serverId: String): List<Cookie> =
        withContext(Dispatchers.IO) {
            // Fold in legacy single-cookie storage once per server scope.
            // This awaits the deferred prefs on the IO dispatcher — never
            // blocks the caller (e.g. main thread) during app startup.
            val migrated = readLegacyCookie()
            val persisted =
                prefs
                    .getString(keyFor(serverId), null)
                    ?.let { raw ->
                        runCatching {
                            json.decodeFromString<List<CookieHolder>>(raw).mapNotNull { it.toCookie() }
                        }.getOrDefault(emptyList())
                    }.orEmpty()
            val merged = (persisted + migrated).distinctBy { Triple(it.name, it.domain, it.path) }
            if (migrated.isNotEmpty()) {
                // Keep the source until the encrypted copy is durable, including across process death.
                val encoded = json.encodeToString(merged.map { it.serialize() })
                check(prefs.edit().putString(keyFor(serverId), encoded).commit()) {
                    "Unable to migrate encrypted cookies"
                }
                legacyPrefsDeferred
                    ?.await()
                    ?.edit()
                    ?.remove(LEGACY_SESSION_COOKIE_KEY)
                    ?.commit()
            }
            merged
        }

    override suspend fun clear(serverId: String) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().remove(keyFor(serverId)).commit()) { "Unable to clear encrypted cookies" }
        }

    override suspend fun clearAll() =
        withContext(Dispatchers.IO) {
            check(prefs.edit().clear().commit()) { "Unable to clear encrypted cookies" }
        }

    /** Read the legacy source without removing it; [load] commits the encrypted copy first. */
    private suspend fun readLegacyCookie(): List<Cookie> {
        val prefs =
            legacyPrefsDeferred?.await()
                ?: return emptyList()
        val legacy =
            prefs.getString(LEGACY_SESSION_COOKIE_KEY, null)
                ?: return emptyList()
        if (legacy.isBlank()) return emptyList()
        return wrapSessionCookie(legacy)?.let { listOf(it) } ?: emptyList()
    }

    private fun keyFor(serverId: String) = "cookies::$serverId"

    companion object {
        const val PREFS_FILE = "hermes_secure_cookies"
        const val LEGACY_SESSION_COOKIE_KEY = "session_cookie"
    }
}
