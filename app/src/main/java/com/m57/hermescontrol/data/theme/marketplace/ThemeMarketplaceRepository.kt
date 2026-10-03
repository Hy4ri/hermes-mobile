package com.m57.hermescontrol.data.theme.marketplace

import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.await
import com.m57.hermescontrol.data.remote.isRetryable
import com.m57.hermescontrol.data.remote.jitteredBackoff
import com.m57.hermescontrol.data.remote.mapHttpError
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Mobile-side theme marketplace catalog fetch + parse (t_5316ccb7).
 *
 * Source of truth is the public VS Code Gallery ExtensionQuery API — the
 * same endpoint the desktop `marketplace-theme-page.tsx` browses. There is
 * no Hermes-hosted catalog; see the t_78b44922 research report for the
 * full analysis.
 *
 * Scope is deliberately catalog-only: search/list + per-extension VSIX URL
 * resolve. Downloading the `.vsix` and converting its themes into Compose
 * palettes belongs to t_f3c6f528 (theme apply pipeline).
 *
 * Networking notes:
 * - Uses a dedicated [OkHttpClient] WITHOUT the app cookie jar / auth
 *   interceptors, so Hermes session cookies never leak to Microsoft.
 * - Results are cached in memory for [cacheTtlMs] (default 15 min), keyed
 *   by `query|limit`. [clearCache] resets it (tests, settings refresh).
 */
class ThemeMarketplaceRepository(
    galleryBaseUrl: String = GALLERY_BASE_URL,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val timeNow: () -> Long = System::currentTimeMillis,
    client: OkHttpClient? = null,
) {
    private val queryUrl = galleryBaseUrl.trimEnd('/') + GALLERY_QUERY_PATH
    private val client: OkHttpClient =
        client ?: defaultClient()

    private val json: Json = Json { ignoreUnknownKeys = true }

    private data class CacheEntry(
        val atMs: Long,
        val entries: List<MarketplaceThemeEntry>,
    )

    private val cacheLock = Any()
    private val cache = mutableMapOf<String, CacheEntry>()
    private val assetsCache = mutableMapOf<String, ThemeAssets>()

    /**
     * Search the marketplace. Empty [query] returns the most-installed
     * themes; otherwise a full-text search scoped to the Themes category.
     * Icon packs are filtered out (gallery has no color-only category).
     *
     * [page] supports the gallery's `pageNumber` for load-more pagination;
     * each `query|limit|page` combo is cached independently for [cacheTtlMs].
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
        page: Int = 1,
    ): NetworkResult<List<MarketplaceThemeEntry>> {
        val pageSize = limit.coerceIn(1, MAX_LIMIT)
        val pageNumber = page.coerceAtLeast(1)
        val key = "${query.trim()}|$pageSize|$pageNumber"
        synchronized(cacheLock) {
            val hit = cache[key]
            if (hit != null && timeNow() - hit.atMs <= cacheTtlMs) {
                return NetworkResult.Success(hit.entries)
            }
        }
        // Over-fetch so the icon-theme filter below still leaves a full page.
        val fetchSize = minOf(pageSize * 2, MAX_FETCH)
        val payload = gallerySearchPayload(query, fetchSize, pageNumber)
        val result: NetworkResult<List<MarketplaceThemeEntry>> =
            postQuery(payload) { response ->
                response.results
                    .firstOrNull()
                    ?.extensions
                    .orEmpty()
                    .filterNot(::looksLikeIconTheme)
                    .take(pageSize)
                    .map(::toEntry)
            }
        return when (result) {
            is NetworkResult.Success -> {
                synchronized(cacheLock) {
                    cache[key] = CacheEntry(timeNow(), result.data)
                }
                result
            }

            is NetworkResult.Failure -> {
                result
            }
        }
    }

    /**
     * Deep metadata for one catalog row: the downloadable `.vsix` URL plus
     * the gallery's default preview icon. The search response carries
     * neither (that's why [MarketplaceThemeEntry] is a lightweight card),
     * so they're resolved per-extension here, once, and cached forever —
     * asset URLs are stable and tiny.
     */
    suspend fun resolveAssets(extensionId: String): NetworkResult<ThemeAssets> {
        val id = extensionId.trim()
        if (!ID_RE.matches(id)) {
            return NetworkResult.Failure(
                NetworkError.Unknown(
                    "Expected a Marketplace id like \"publisher.extension\".",
                    IllegalArgumentException(id),
                ),
            )
        }
        synchronized(cacheLock) {
            assetsCache[id]?.let { return NetworkResult.Success(it) }
        }
        val result: NetworkResult<ThemeAssets> =
            postQuery(galleryResolvePayload(id)) { response ->
                val extension =
                    response.results
                        .firstOrNull()
                        ?.extensions
                        ?.firstOrNull()
                        ?: throw NoSuchElementException("Extension \"$id\" was not found on the Marketplace.")
                val version =
                    extension.versions.firstOrNull()
                        ?: throw NoSuchElementException(
                            "Extension \"$id\" has no published versions.",
                        )
                val files = version.files
                val downloadUrl =
                    files.firstOrNull { it.assetType == VSIX_ASSET_TYPE }?.source?.takeIf { it.isNotBlank() }
                        ?: throw NoSuchElementException("Could not find a downloadable package for \"$id\".")
                val previewUrl =
                    files
                        .firstOrNull {
                            it.assetType == ICON_ASSET_TYPE
                        }?.source
                        ?.takeIf { it.isNotBlank() }
                ThemeAssets(downloadUrl = downloadUrl, previewUrl = previewUrl)
            }
        if (result is NetworkResult.Success) {
            synchronized(cacheLock) {
                assetsCache[id] = result.data
            }
        }
        return result
    }

    /**
     * Resolve the downloadable `.vsix` URL for one `publisher.extension` id.
     * Used by the downstream install step after the user picks a catalog row.
     */
    suspend fun fetchDownloadUrl(extensionId: String): NetworkResult<String> =
        when (val assets = resolveAssets(extensionId)) {
            is NetworkResult.Success -> NetworkResult.Success(assets.data.downloadUrl)
            is NetworkResult.Failure -> assets
        }

    /** Clear the in-memory catalog + asset caches. */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            assetsCache.clear()
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private suspend fun <T> postQuery(
        payload: GalleryQueryPayload,
        parse: (GalleryResponse) -> T,
    ): NetworkResult<T> {
        val bodyJson = json.encodeToString(GalleryQueryPayload.serializer(), payload)
        var lastIo: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val request =
                Request
                    .Builder()
                    .url(queryUrl)
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json;api-version=3.0-preview.1")
                    .header("User-Agent", USER_AGENT)
                    .build()
            try {
                val response = client.newCall(request).await()
                response.use {
                    if (!it.isSuccessful) {
                        val code = it.code
                        if ((code in 500..599 || code == 429) && attempt < MAX_ATTEMPTS - 1) {
                            delay(jitteredBackoff(RETRY_BASE_MS * (1L shl attempt)))
                            return@repeat
                        }
                        return NetworkResult.Failure(mapHttpError(code))
                    }
                    val text = it.body.string()
                    if (text.length > MAX_RESPONSE_BYTES) {
                        return NetworkResult.Failure(
                            NetworkError.Unknown(
                                "Marketplace response exceeded the size limit.",
                                IllegalStateException(),
                            ),
                        )
                    }
                    return try {
                        NetworkResult.Success(parse(json.decodeFromString(GalleryResponse.serializer(), text)))
                    } catch (e: Exception) {
                        NetworkResult.Failure(
                            NetworkError.Unknown("Could not parse the Marketplace response: ${e.message}", e),
                        )
                    }
                }
            } catch (e: IOException) {
                lastIo = e
                if (attempt == MAX_ATTEMPTS - 1 || !isRetryable(e)) {
                    return NetworkResult.Failure(
                        NetworkError.Connection("Marketplace connection failure: ${e.message ?: "unknown error"}", e),
                    )
                }
                delay(jitteredBackoff(RETRY_BASE_MS * (1L shl attempt)))
            } catch (e: Exception) {
                return NetworkResult.Failure(
                    NetworkError.Unknown("Marketplace request failed: ${e.message}", e),
                )
            }
        }
        return NetworkResult.Failure(
            NetworkError.Connection(
                "Marketplace connection failure after $MAX_ATTEMPTS attempts: ${lastIo?.message}",
                lastIo ?: IOException("Unknown connection error"),
            ),
        )
    }

    private fun toEntry(extension: GalleryExtension): MarketplaceThemeEntry {
        val publisherName = extension.publisher?.publisherName.orEmpty()
        val installs =
            extension.statistics
                .firstOrNull { it.statisticName == "install" }
                ?.value
                ?.roundToLong() ?: 0L
        return MarketplaceThemeEntry(
            extensionId = "$publisherName.${extension.extensionName}",
            displayName = extension.displayName.ifBlank { extension.extensionName },
            publisher = extension.publisher?.displayName?.ifBlank { publisherName } ?: publisherName,
            description = extension.shortDescription.orEmpty(),
            installs = installs,
        )
    }

    companion object {
        const val GALLERY_BASE_URL: String = "https://marketplace.visualstudio.com"
        const val GALLERY_QUERY_PATH: String = "/_apis/public/gallery/extensionquery"
        const val MAX_LIMIT: Int = 50
        const val CACHE_TTL_MS: Long = 15 * 60 * 1_000L

        private const val MAX_FETCH: Int = 50
        private const val MAX_ATTEMPTS: Int = 3
        private const val RETRY_BASE_MS: Long = 500L
        private const val MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
        private const val VSIX_ASSET_TYPE: String = "Microsoft.VisualStudio.Services.VSIXPackage"
        private const val ICON_ASSET_TYPE: String = "Microsoft.VisualStudio.Services.Icons.Default"
        private const val USER_AGENT: String = "Hermes-Mobile"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ID_RE = Regex("""^[\w-]+\.[\w-]+$""")
        private val ICON_TEXT_RE = Regex("""\b(icon theme|file icons?|product icons?|icon pack|fileicons)\b""")

        /**
         * The Themes category also contains file-icon/product-icon packs.
         * Ported verbatim from `looksLikeIconTheme` in vscode-marketplace.ts.
         */
        internal fun looksLikeIconTheme(extension: GalleryExtension): Boolean {
            val tags = extension.tags.map { it.lowercase() }
            if ("icon-theme" in tags || "product-icon-theme" in tags) {
                return true
            }
            val text = "${extension.displayName} ${extension.shortDescription}".lowercase()
            return ICON_TEXT_RE.containsMatchIn(text)
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
