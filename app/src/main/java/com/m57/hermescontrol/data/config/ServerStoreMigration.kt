package com.m57.hermescontrol.data.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.serialization.decodeFromString

class ServerStoreMigration(
    private val context: Context,
) : DataMigration<ServerStoreState> {
    companion object {
        private const val PREFS_FILE = "hermes_secure_prefs"
        private const val KEY_MIGRATED = "migrated_to_datastore"
        private val LEGACY_KEYS =
            setOf(
                "host",
                "port",
                "auto_reconnect",
                "theme_preference",
                "use_dynamic_colors",
                "theme_preset",
                "connection_profiles",
                "selected_profile_id",
                "pinned_models",
                "ws_auth_param",
                "typing_effect_enabled",
                "typing_effect_delay_ms",
                "auth_token",
            )
    }

    override suspend fun shouldMigrate(currentData: ServerStoreState): Boolean {
        val prefs = getPrefs() ?: return false
        if (prefs.getBoolean(KEY_MIGRATED, false)) return false
        return LEGACY_KEYS.any(prefs::contains)
    }

    override suspend fun migrate(currentData: ServerStoreState): ServerStoreState {
        val prefs = getPrefs() ?: return currentData
        Log.d("ServerStoreMigration", "Migrating legacy preferences to DataStore...")

        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", 9119)
        val autoReconnect = prefs.getBoolean("auto_reconnect", true)

        val themePrefString = prefs.getString("theme_preference", ThemePreference.SYSTEM.name)
        val themePreference =
            runCatching { ThemePreference.valueOf(themePrefString!!) }
                .getOrDefault(ThemePreference.SYSTEM)

        val useDynamicColors = prefs.getBoolean("use_dynamic_colors", true)

        val themePresetString = prefs.getString("theme_preset", ThemePreset.DEFAULT.name)
        val themePreset =
            runCatching { ThemePreset.valueOf(themePresetString!!) }
                .getOrDefault(ThemePreset.DEFAULT)

        val connectionProfilesRaw = prefs.getString("connection_profiles", null)
        val connectionProfiles =
            if (connectionProfilesRaw != null) {
                try {
                    OkHttpProvider.json.decodeFromString<List<ConnectionProfile>>(connectionProfilesRaw)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val selectedProfileId = prefs.getString("selected_profile_id", null)?.takeIf { it.isNotBlank() }

        val pinnedModelsRaw = prefs.getString("pinned_models", null)
        val pinnedModels =
            if (pinnedModelsRaw != null) {
                try {
                    OkHttpProvider.json.decodeFromString<List<PinnedModel>>(pinnedModelsRaw)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val wsAuthParam = prefs.getString("ws_auth_param", "token") ?: "token"
        val typingEffectEnabled = prefs.getBoolean("typing_effect_enabled", true)
        val typingEffectDelayMs = prefs.getInt("typing_effect_delay_ms", 30)

        return currentData.copy(
            host = host,
            port = port,
            baseUrl = ServerEndpoint.fromLegacy(host, port).baseUrl.toString(),
            autoReconnect = autoReconnect,
            themePreference = themePreference,
            useDynamicColors = useDynamicColors,
            themePreset = themePreset,
            connectionProfiles =
                connectionProfiles.map { profile ->
                    profile.copy(
                        baseUrl =
                            profile.baseUrl
                                ?: ServerEndpoint.fromLegacy(
                                    profile.host,
                                    profile.port,
                                ).baseUrl.toString(),
                    )
                },
            selectedProfileId = selectedProfileId,
            pinnedModels = pinnedModels,
            wsAuthParam = wsAuthParam,
            typingEffectEnabled = typingEffectEnabled,
            typingEffectDelayMs = typingEffectDelayMs,
        )
    }

    override suspend fun cleanUp() {
        val prefs = getPrefs() ?: return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        Log.d("ServerStoreMigration", "Migration cleanup complete.")
    }

    private fun getPrefs(): android.content.SharedPreferences? =
        try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKey,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            null
        }
}
