package com.m57.hermescontrol.data.theme.import

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.ThemePalette
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.setCustomPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Decode a persisted `customThemeTokensJson` payload back into a
 * [ThemeTokenSet] family.
 *
 * Returns `null` for every unusable input — blank/absent, malformed JSON, or a
 * valid-but-empty list — so callers can treat "cannot restore" as one branch
 * (spec M6) instead of three. Kept top-level and pure so the failure path is
 * unit-testable without `AuthManager`/`EncryptedSharedPreferences`.
 */
internal fun decodePersistedVariants(tokensJson: String?): List<ThemeTokenSet>? {
    if (tokensJson.isNullOrBlank()) return null
    return runCatching {
        json.decodeFromString(ListSerializer(ThemeTokenSet.serializer()), tokensJson)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * Applies marketplace themes to the running app (t_f3c6f528).
 *
 * Converts a [ThemeTokenSet] family into a [ThemePalette] via
 * [ThemeDefinitionConverter.buildFamily], publishes it to
 * `Theme.kt` (consumed by the existing dispatcher as `ThemePreset.CUSTOM`),
 * persists the raw tokens in [ServerStoreState] so the theme survives
 * restarts, and switches the preset to CUSTOM. The six built-in presets are
 * untouched — clearing the custom theme returns to the stored preset.
 */
object ThemeApplier {
    private val converter = ThemeDefinitionConverter()

    private val _activeCustomThemeName = MutableStateFlow<String?>(null)
    val activeCustomThemeName: StateFlow<String?> = _activeCustomThemeName.asStateFlow()

    private val _activeCustomThemeId = MutableStateFlow<String?>(null)
    val activeCustomThemeId: StateFlow<String?> = _activeCustomThemeId.asStateFlow()

    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

    private val _restoreFailed = MutableStateFlow<Boolean>(false)
    val restoreFailed: StateFlow<Boolean> = _restoreFailed.asStateFlow()

    /**
     * Display name of the theme that could not be restored, so the UI can say
     * *which* import is unavailable instead of showing a generic label. Null
     * whenever [restoreFailed] is false.
     */
    private val _unavailableThemeName = MutableStateFlow<String?>(null)
    val unavailableThemeName: StateFlow<String?> = _unavailableThemeName.asStateFlow()

    /**
     * Gallery id of the theme that could not be restored, so the UI can offer a
     * real re-apply (search the catalog for this id) instead of a dead button.
     * Null whenever [restoreFailed] is false.
     */
    private val _unavailableThemeId = MutableStateFlow<String?>(null)
    val unavailableThemeId: StateFlow<String?> = _unavailableThemeId.asStateFlow()

    /** Apply a marketplace theme family; persists + selects CUSTOM. */
    fun applyFamily(
        extensionId: String,
        displayName: String,
        variants: List<ThemeTokenSet>,
    ) {
        val palette =
            try {
                converter.buildFamily(variants)
            } catch (e: Exception) {
                _applyError.value = "Could not convert theme: ${e.message}"
                return
            }
        setCustomPalette(palette)
        _activeCustomThemeId.value = extensionId
        _activeCustomThemeName.value = displayName
        _applyError.value = null
        _restoreFailed.value = false
        _unavailableThemeName.value = null
        _unavailableThemeId.value = null
        AuthManager.serverStore.update {
            it.copy(
                themePreset = ThemePreset.CUSTOM,
                customThemeId = extensionId,
                customThemeName = displayName,
                customThemeTokensJson =
                    runCatching {
                        json.encodeToString(ListSerializer(ThemeTokenSet.serializer()), variants)
                    }.getOrNull(),
            )
        }
    }

    /** Clear the custom theme; preset returns to [fallback]. */
    fun clearCustomTheme(fallback: ThemePreset = ThemePreset.DEFAULT) {
        setCustomPalette(null)
        _activeCustomThemeId.value = null
        _activeCustomThemeName.value = null
        _applyError.value = null
        _restoreFailed.value = false
        _unavailableThemeName.value = null
        _unavailableThemeId.value = null
        AuthManager.serverStore.update {
            it.copy(
                themePreset = fallback,
                customThemeId = null,
                customThemeName = null,
                customThemeTokensJson = null,
            )
        }
    }

    /**
     * Restore a persisted custom theme after process start. Called from
     * `AuthManager.init` once the server store is loaded.
     *
     * Never throws. On success the palette is published and the theme is
     * active. On any unusable input (blank, malformed, or an empty variant
     * list) it records [restoreFailed] + [unavailableThemeName] and leaves the
     * palette null, so the dispatcher falls back to Default and the UI can say
     * so honestly instead of showing a fake "Marketplace theme active" label
     * (spec M6). Note [restoreFailed] is only meaningful together with
     * `themePreset == CUSTOM` — init calls this unconditionally, so a blank
     * payload for a built-in preset must not raise the recovery banner.
     */
    fun restorePersisted(
        id: String?,
        name: String?,
        tokensJson: String?,
    ) {
        _restoreFailed.value = false
        _unavailableThemeName.value = null
        _unavailableThemeId.value = null
        val variants = decodePersistedVariants(tokensJson)
        if (variants == null) {
            markUnavailable(id, name)
            return
        }
        val palette = runCatching { converter.buildFamily(variants) }.getOrNull()
        if (palette == null) {
            markUnavailable(id, name)
            return
        }
        setCustomPalette(palette)
        _activeCustomThemeId.value = id
        _activeCustomThemeName.value = name
    }

    private fun markUnavailable(
        id: String?,
        name: String?,
    ) {
        _restoreFailed.value = true
        _unavailableThemeId.value = id
        _unavailableThemeName.value = name
        // Leave the palette null so Theme.kt's CUSTOM -> Default fallback holds
        // for the whole process; the banner offers re-apply or clear.
        _activeCustomThemeId.value = null
        _activeCustomThemeName.value = null
    }
}
