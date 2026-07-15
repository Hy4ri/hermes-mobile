package com.m57.hermescontrol.util

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import java.util.Locale

/**
 * Static helpers for applying a user-selected in-app display language.
 *
 * Hermes Mobile's [com.m57.hermescontrol.MainActivity] extends the plain
 * `ComponentActivity` (not `AppCompatActivity`), so we apply the locale through
 * `attachBaseContext` + [ContextWrapper.wrap] rather than relying on
 * AppCompatDelegate. Works on minSdk 26.
 */
object LocaleContextWrapper {
    const val SYSTEM_LANGUAGE = "system"

    /**
     * Resolve a language code ("system", "en", "ko", …) into a [Locale].
     *
     * "system" returns the device's default locale (so passing it through
     * [wrap] is effectively a no-op). Codes containing a region separator
     * ("zh-rCN", "pt-BR") are split into language + country.
     */
    fun localeForCode(code: String): Locale =
        when {
            code.isEmpty() || code == SYSTEM_LANGUAGE -> Locale.getDefault()
            code.contains("-r", ignoreCase = true) -> {
                val parts = code.split("-r", ignoreCase = true)
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            code.contains("-") -> {
                val parts = code.split("-")
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            code.contains("_") -> {
                val parts = code.split("_")
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            else -> Locale(code)
        }

    /**
     * Wrap [base] so the configuration carries [locale].
     *
     * On API 33+ we use [Context.createConfigurationContext] which is the only
     * reliable path once the deprecated `Configuration.locale` / `setLocale`
     * APIs were removed from the public surface.
     */
    fun wrap(
        base: Context,
        locale: Locale,
    ): Context {
        val config = base.resources.configuration
        val newConfig =
            android.content.res.Configuration(config).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setLocales(android.os.LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    setLocale(locale)
                }
            }
        return base.createConfigurationContext(newConfig)
    }

    /** Convenience: wrap [base] using a stored language code. */
    fun wrapWithCode(
        base: Context,
        code: String,
    ): Context = wrap(base, localeForCode(code))
}
