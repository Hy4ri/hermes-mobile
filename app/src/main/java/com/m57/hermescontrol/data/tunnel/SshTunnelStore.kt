package com.m57.hermescontrol.data.tunnel

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Locale

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
    private var configured: Map<String, SshTunnelConfig> = emptyMap()

    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(encryptedPreferences: SharedPreferences) {
        val stored = encryptedPreferences.getString(KEY, null)
        configured =
            if (stored == null) {
                emptyMap()
            } else {
                try {
                    json.decodeFromString<Map<String, SshTunnelConfig>>(stored)
                } catch (_: SerializationException) {
                    // Parser messages echo the input, which contains credentials.
                    throw IOException("Could not read SSH tunnel settings")
                }
            }
        preferences = encryptedPreferences
    }

    fun get(origin: String?): SshTunnelConfig? = origin?.let { configured[it] }

    fun all(): Map<String, SshTunnelConfig> = configured

    /** Call on IO. Publishes only after the encrypted write succeeds. */
    @Synchronized
    fun save(
        origin: String,
        config: SshTunnelConfig,
    ) {
        val prefs = checkNotNull(preferences) { "SSH tunnel settings are not initialized" }
        val updated = configured.toMutableMap().apply { put(origin, config) }
        write(prefs, updated)
    }

    @Synchronized
    fun delete(origin: String) {
        val prefs = checkNotNull(preferences) { "SSH tunnel settings are not initialized" }
        val updated = configured.toMutableMap().apply { remove(origin) }
        write(prefs, updated)
    }

    private fun write(
        prefs: SharedPreferences,
        updated: Map<String, SshTunnelConfig>,
    ) {
        if (!prefs.edit().putString(KEY, json.encodeToString(updated)).commit()) {
            throw IOException("Could not save SSH tunnel settings")
        }
        configured = updated
    }

    /** Origin key with a stable lowercase host, matching [tunnelOrigin]. */
    fun originKey(origin: String): String = origin.lowercase(Locale.ROOT)
}
