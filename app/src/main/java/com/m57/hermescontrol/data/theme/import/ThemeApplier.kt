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
    private val json = Json { ignoreUnknownKeys = true }

    private val converter = ThemeDefinitionConverter()

    private val _activeCustomThemeName = MutableStateFlow<String?>(null)
    val activeCustomThemeName: StateFlow<String?> = _activeCustomThemeName.asStateFlow()

    private val _activeCustomThemeId = MutableStateFlow<String?>(null)
    val activeCustomThemeId: StateFlow<String?> = _activeCustomThemeId.asStateFlow()

    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

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
     * `AuthManager.init` once the server store is loaded; no-op when nothing
     * was persisted or conversion fails (dispatcher falls back to Default).
     */
    fun restorePersisted(
        id: String?,
        name: String?,
        tokensJson: String?,
    ) {
        if (tokensJson.isNullOrBlank()) return
        val variants =
            runCatching {
                json.decodeFromString(ListSerializer(ThemeTokenSet.serializer()), tokensJson)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val palette = runCatching { converter.buildFamily(variants) }.getOrNull() ?: return
        setCustomPalette(palette)
        _activeCustomThemeId.value = id
        _activeCustomThemeName.value = name
    }
}
