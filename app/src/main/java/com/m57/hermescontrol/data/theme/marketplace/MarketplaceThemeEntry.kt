package com.m57.hermescontrol.data.theme.marketplace

import kotlinx.serialization.Serializable

/**
 * One row of the theme marketplace catalog (t_5316ccb7).
 *
 * Mirrors the lightweight card shaped by the desktop Electron fetcher
 * (`searchMarketplaceThemes` in `vscode-marketplace.ts`): identity +
 * display metadata + install count. The downloadable package URL is NOT
 * part of the search response — resolve it per-extension with
 * [ThemeMarketplaceRepository.fetchDownloadUrl] when the user picks a row.
 */
@Serializable
data class MarketplaceThemeEntry(
    /** Gallery id, e.g. `dracula-theme.theme-dracula`. */
    val extensionId: String,
    val displayName: String,
    val publisher: String,
    val description: String,
    /** Rounded install count; 0 when the gallery omits statistics. */
    val installs: Long,
)

/**
 * Resolved deep metadata for one catalog row (see
 * [ThemeMarketplaceRepository.resolveAssets]): the downloadable `.vsix`
 * package plus the gallery's preview icon.
 */
@Serializable
data class ThemeAssets(
    val downloadUrl: String,
    val previewUrl: String? = null,
)
