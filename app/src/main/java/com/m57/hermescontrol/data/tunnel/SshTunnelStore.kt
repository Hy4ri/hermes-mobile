package com.m57.hermescontrol.data.tunnel

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Per-origin SSH tunnel settings, persisted in AuthManager's encrypted
 * preferences (never in the plaintext `server_store.json`), because the file
 * holds the SSH password or private key.
 *
 * The origin key is `scheme://host:port` — the same dashboard address the
 * routing hooks match on.
 */
object SshTunnelStore {
    private const val KEY = "ssh_tunnel_configs"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var loadFailed: Boolean = false
        private set

    @Volatile
    private var configured: Map<String, SshTunnelConfig> = emptyMap()

    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(encryptedPreferences: SharedPreferences) {
        preferences = encryptedPreferences
        configured = emptyMap()
        loadFailed = true
        try {
            val stored = encryptedPreferences.getString(KEY, null)
            configured =
                if (stored == null) {
                    emptyMap()
                } else {
                    json.decodeFromString<Map<String, SshTunnelConfig>>(stored)
                }
            loadFailed = false
        } catch (_: Exception) {
            configured = emptyMap()
            loadFailed = true
            // Parser or keystore/prefs messages might echo inputs containing credentials.
            throw IOException("Could not read SSH tunnel settings")
        }
    }

    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun resetForTest() {
        preferences = null
        configured = emptyMap()
        loadFailed = false
    }

    fun get(origin: String?): SshTunnelConfig? = origin?.let { configured[it] }

    fun all(): Map<String, SshTunnelConfig> = configured

    /** Call on IO. Publishes only after the encrypted write succeeds. */
    @Synchronized
    fun save(
        origin: String,
        config: SshTunnelConfig,
    ) {
        if (loadFailed) {
            throw IOException("SSH tunnel settings failed to load; cannot overwrite")
        }
        val prefs = preferences ?: throw IOException("SSH tunnel settings are not initialized")
        val updated = configured.toMutableMap().apply { put(origin, config) }
        write(prefs, updated)
    }

    @Synchronized
    fun delete(origin: String) {
        if (loadFailed) {
            throw IOException("SSH tunnel settings failed to load; cannot overwrite")
        }
        val prefs = preferences ?: throw IOException("SSH tunnel settings are not initialized")
        val updated = configured.toMutableMap().apply { remove(origin) }
        write(prefs, updated)
    }

    private fun write(
        prefs: SharedPreferences,
        updated: Map<String, SshTunnelConfig>,
    ) {
        val success =
            try {
                prefs.edit().putString(KEY, json.encodeToString(updated)).commit()
            } catch (_: Exception) {
                throw IOException("Could not save SSH tunnel settings")
            }
        if (!success) {
            throw IOException("Could not save SSH tunnel settings")
        }
        configured = updated
    }
}
