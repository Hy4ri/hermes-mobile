package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Latest release metadata from the GitHub releases API (issue #867) — the
 * in-app self-update source. Fields map to the JSON via the app's shared
 * snake_case [OkHttpProvider.json].
 */
@Serializable
data class UpdateInfo(
    val tagName: String = "",
    val body: String = "",
    /** GitHub release flags: a draft is unpublished, a prerelease is pre-stable. */
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(
        val name: String = "",
        val size: Long = 0L,
        val browserDownloadUrl: String = "",
    )

    /** The release APK asset — release.yml ships `hermes-mobile-<tag>.apk`. */
    val apkAsset: Asset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

/** Strip a leading 'v' from a release tag ("v1.21.0" → "1.21.0"). */
fun normalizedVersion(tag: String): String = tag.trim().removePrefix("v")

/**
 * True when [latest] is strictly newer than [current] (issue #867).
 * Compare the numeric core first, padding missing parts with zero, then
 * prerelease identifiers. Stable outranks prerelease for the same core.
 * Accept both "1.25.0-rc.1" and legacy "1.25.rc.1"; ignore build metadata.
 * Invalid versions never claim an update exists.
 */
fun isNewerVersion(
    latest: String,
    current: String,
): Boolean {
    val a = parseVersion(latest) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.core.size, b.core.size)) {
        val comparison = a.core.getOrElse(i) { 0 }.compareTo(b.core.getOrElse(i) { 0 })
        if (comparison != 0) return comparison > 0
    }
    if (a.prerelease.isEmpty()) return b.prerelease.isNotEmpty()
    if (b.prerelease.isEmpty()) return false
    for (i in 0 until minOf(a.prerelease.size, b.prerelease.size)) {
        val comparison = comparePrereleaseIdentifier(a.prerelease[i], b.prerelease[i])
        if (comparison != 0) return comparison > 0
    }
    return a.prerelease.size > b.prerelease.size
}

/**
 * Pick the newest installable release out of a GitHub `releases` list payload.
 *
 * Drafts and releases without an APK asset are always ignored. Pre-releases are
 * only eligible when [includeReleaseCandidates] is set *and* the tag is a
 * release candidate — alpha/beta/dev pre-releases stay out of the RC channel.
 *
 * Selection uses [isNewerVersion] rather than list order because the API orders
 * releases by publication date, which is not a version oracle.
 */
fun selectLatestUpdate(
    releases: List<UpdateInfo>,
    includeReleaseCandidates: Boolean = false,
): UpdateInfo? =
    releases
        .filter(::hasUsableApk)
        .filter { isEligibleRelease(it, includeReleaseCandidates) }
        .reduceOrNull { best, candidate ->
            if (isNewerVersion(candidate.tagName, best.tagName)) candidate else best
        }

private fun hasUsableApk(release: UpdateInfo): Boolean =
    !release.draft && release.apkAsset?.browserDownloadUrl?.toHttpUrlOrNull()?.let {
        it.scheme == "http" || it.scheme == "https"
    } == true

private fun isEligibleRelease(
    release: UpdateInfo,
    includeReleaseCandidates: Boolean,
): Boolean {
    val version = parseVersion(release.tagName) ?: return false
    val isRc = version.prerelease.firstOrNull()?.let(RC_IDENTIFIER::matches) == true
    val isStable = version.prerelease.isEmpty() && !release.prerelease
    if (isStable) return true
    return includeReleaseCandidates && isRc
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>,
)

private val VERSION_PATTERN =
    Regex(
        """^([0-9]+(?:\.[0-9]+)*)(?:(?:-|\.(?=[a-zA-Z]))([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*))?(?:\+[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*)?$""",
    )

private fun parseVersion(version: String): ParsedVersion? {
    val match = VERSION_PATTERN.matchEntire(normalizedVersion(version)) ?: return null
    val core = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    val prerelease =
        match.groupValues[2]
            .takeIf { it.isNotEmpty() }
            ?.split('.')
            .orEmpty()
    return ParsedVersion(core, prerelease)
}

private fun comparePrereleaseIdentifier(
    a: String,
    b: String,
): Int {
    val aNumeric = a.all { it in '0'..'9' }
    val bNumeric = b.all { it in '0'..'9' }
    if (aNumeric != bNumeric) return if (aNumeric) -1 else 1
    if (!aNumeric) return a.compareTo(b)
    // Compare arbitrarily large RC numbers without integer overflow.
    val x = a.trimStart('0')
    val y = b.trimStart('0')
    return x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
}

/** `rc`, `rc1`, `RC2` — but never alpha/beta/dev. */
private val RC_IDENTIFIER = Regex("^rc[0-9]*$", RegexOption.IGNORE_CASE)

/**
 * True when [tag] is a release-candidate version such as "v1.25.0-rc.1",
 * "v1.25-rc1", or the legacy dot form "v1.25.rc.1".
 *
 * Only the first pre-release identifier is inspected, so alpha/beta builds are
 * never treated as release candidates.
 */
fun isReleaseCandidateVersion(tag: String): Boolean =
    parseVersion(tag)?.prerelease?.firstOrNull()?.let(RC_IDENTIFIER::matches) == true

/**
 * Talks to the GitHub releases API and downloads the release APK (issue
 * #867). Network methods are `open` so tests can fake them; the pure logic
 * ([isNewerVersion], [parseUpdateInfo]) lives top-level for direct unit
 * tests.
 */
open class AppUpdateChecker(
    private val client: OkHttpClient = OkHttpProvider.external,
    private val apiBaseUrl: String = "https://api.github.com/repos/Hy4ri/hermes-mobile",
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val metadataTtlMs: Long = 60_000L,
) {
    private val scanMutex = Mutex()

    /**
     * Fetch the newest installable release metadata.
     *
     * Stable-only by default: the `releases/latest` endpoint deliberately
     * excludes pre-releases. With [includeReleaseCandidates] the releases list
     * is scanned instead so a newer release-candidate tag becomes eligible.
     *
     * Returns null when there is no usable release (404) or the body can't be
     * parsed. Throws [IOException] on network failure so the caller can
     * surface a friendly error.
     */
    open suspend fun fetchLatestRelease(includeReleaseCandidates: Boolean = false): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val channel = if (includeReleaseCandidates) Channel.RC else Channel.STABLE
            val cacheKey = CacheKey(apiBaseUrl, channel)
            val currentTime = nowMs()

            cachedEntries[cacheKey]?.let { entry ->
                if (currentTime < entry.timestampMs + metadataTtlMs) {
                    return@withContext entry.result
                }
            }

            scanMutex.withLock {
                val lockedTime = nowMs()
                cachedEntries[cacheKey]?.let { entry ->
                    if (lockedTime < entry.timestampMs + metadataTtlMs) {
                        return@withContext entry.result
                    }
                }

                val result = executeScan(includeReleaseCandidates)
                cachedEntries[cacheKey] = CachedResult(result, lockedTime)
                result
            }
        }

    /** Release metadata for an exact [tag] (e.g. "v1.31.0"); null when GitHub has no such release. */
    open suspend fun fetchReleaseByTag(tag: String): UpdateInfo? =
        withContext(Dispatchers.IO) { fetchSingleRelease("$apiBaseUrl/releases/tags/$tag") }

    private suspend fun executeScan(includeReleaseCandidates: Boolean): UpdateInfo? {
        val latest = fetchSingleRelease("$apiBaseUrl/releases/latest")
        if (!includeReleaseCandidates && latest != null && selectLatestUpdate(listOf(latest)) != null) {
            return latest
        }
        val releases = fetchReleasePages() ?: return null
        return selectLatestUpdate(releases + listOfNotNull(latest), includeReleaseCandidates)
    }

    private suspend fun fetchSingleRelease(url: String): UpdateInfo? {
        val cached = pageCache[url]
        val (page, status) = getConditional(url, cached)
        return when (status) {
            PageFetchStatus.NOT_FOUND -> {
                pageCache.remove(url)
                null
            }

            PageFetchStatus.NOT_MODIFIED -> {
                val parsed =
                    cached?.metadata as? UpdateInfo
                        ?: throw IOException("GitHub returned 304 without cached release metadata")
                if (page?.etag != null || page?.lastModified != null) {
                    pageCache[url] =
                        cached.copy(
                            etag = page.etag ?: cached.etag,
                            lastModified = page.lastModified ?: cached.lastModified,
                        )
                }
                parsed
            }

            PageFetchStatus.OK -> {
                val nonNullPage = page ?: throw IOException("Empty response from GitHub")
                val parsed =
                    parseUpdateInfo(nonNullPage.body)
                        ?: throw IOException("GitHub returned malformed release metadata")
                pageCache[url] =
                    CachedPage(
                        etag = nonNullPage.etag,
                        lastModified = nonNullPage.lastModified,
                        body = nonNullPage.body,
                        nextUrl = nonNullPage.nextUrl,
                        metadata = parsed,
                    )
                parsed
            }
        }
    }

    private suspend fun fetchReleasePages(): List<UpdateInfo>? {
        var nextUrl: String? = "$apiBaseUrl/releases?per_page=$RELEASE_LIST_PAGE_SIZE&page=1"
        val releases = mutableListOf<UpdateInfo>()
        val visited = mutableSetOf<String>()

        while (nextUrl != null) {
            if (!visited.add(nextUrl)) {
                throw IOException("Detected pagination cycle in GitHub releases at $nextUrl")
            }

            val cached = pageCache[nextUrl]
            val (page, status) = getConditional(nextUrl, cached)

            when (status) {
                PageFetchStatus.NOT_FOUND -> {
                    pageCache.remove(nextUrl)
                    if (releases.isEmpty()) {
                        return null
                    } else {
                        throw IOException("Incomplete GitHub releases list")
                    }
                }

                PageFetchStatus.NOT_MODIFIED -> {
                    @Suppress("UNCHECKED_CAST")
                    val parsed =
                        cached?.metadata as? List<UpdateInfo>
                            ?: throw IOException("GitHub returned 304 without cached releases list")
                    releases += parsed
                    val resolvedNextUrl = if (page?.nextUrl != null) page.nextUrl else cached.nextUrl
                    if (page?.etag != null || page?.lastModified != null || page?.nextUrl != null) {
                        pageCache[nextUrl] =
                            cached.copy(
                                etag = page?.etag ?: cached.etag,
                                lastModified = page?.lastModified ?: cached.lastModified,
                                nextUrl = resolvedNextUrl,
                            )
                    }
                    nextUrl = resolvedNextUrl
                }

                PageFetchStatus.OK -> {
                    val nonNullPage = page ?: throw IOException("Empty response from GitHub")
                    val parsed =
                        parseReleaseList(nonNullPage.body)
                            ?: throw IOException("GitHub returned malformed release metadata")
                    pageCache[nextUrl] =
                        CachedPage(
                            etag = nonNullPage.etag,
                            lastModified = nonNullPage.lastModified,
                            body = nonNullPage.body,
                            nextUrl = nonNullPage.nextUrl,
                            metadata = parsed,
                        )
                    releases += parsed
                    nextUrl = nonNullPage.nextUrl
                }
            }
        }
        return releases
    }

    private enum class PageFetchStatus {
        OK,
        NOT_MODIFIED,
        NOT_FOUND,
    }

    private data class FetchResult(
        val page: ReleasePage?,
        val status: PageFetchStatus,
    )

    private suspend fun getConditional(
        url: String,
        cached: CachedPage?,
    ): FetchResult =
        suspendCancellableCoroutine { continuation ->
            val builder =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")

            if (cached != null) {
                if (cached.etag != null) {
                    builder.header("If-None-Match", cached.etag)
                } else if (cached.lastModified != null) {
                    builder.header("If-Modified-Since", cached.lastModified)
                }
            }

            val request = builder.build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            response.use { res ->
                                when (res.code) {
                                    HTTP_NOT_FOUND -> {
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                FetchResult(null, PageFetchStatus.NOT_FOUND),
                                            ) { _, _, _ -> }
                                        }
                                    }

                                    HTTP_NOT_MODIFIED -> {
                                        val nextUrl = res.nextPageUrl()
                                        val page = if (nextUrl != null) ReleasePage("", nextUrl, null, null) else null
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                FetchResult(page, PageFetchStatus.NOT_MODIFIED),
                                            ) { _, _, _ -> }
                                        }
                                    }

                                    else -> {
                                        if (!res.isSuccessful) {
                                            if (continuation.isActive) {
                                                continuation.resumeWith(
                                                    Result.failure(
                                                        IOException("GitHub releases request failed (${res.code})"),
                                                    ),
                                                )
                                            }
                                        } else {
                                            val body = res.body.string()
                                            val nextUrl = res.nextPageUrl()
                                            val etag = res.header("ETag")
                                            val lastModified = res.header("Last-Modified")
                                            val releasePage = ReleasePage(body, nextUrl, etag, lastModified)
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    FetchResult(releasePage, PageFetchStatus.OK),
                                                ) { _, _, _ -> }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(e))
                            }
                        }
                    }
                },
            )
        }

    private fun Response.nextPageUrl(): String? {
        val link = header("Link") ?: return null
        val url = NEXT_LINK.find(link)?.groupValues?.get(1) ?: return null
        val next = url.toHttpUrlOrNull() ?: throw IOException("GitHub returned an invalid pagination URL")
        val base = apiBaseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid GitHub API base URL")
        val expectedReposPath = base.encodedPath.trimEnd('/') + "/releases"
        val isReposPath = next.encodedPath == expectedReposPath
        val isRepositoriesPath = REPOSITORIES_RELEASES_PATH.matches(next.encodedPath)
        val isTrustedOrigin = next.scheme == base.scheme && next.host == base.host && next.port == base.port
        if (!isTrustedOrigin || (!isReposPath && !isRepositoriesPath)) {
            throw IOException("GitHub returned an untrusted pagination URL")
        }
        return next.toString()
    }

    private data class ReleasePage(
        val body: String,
        val nextUrl: String?,
        val etag: String?,
        val lastModified: String?,
    )

    private data class CachedPage(
        val etag: String?,
        val lastModified: String?,
        val body: String,
        val nextUrl: String?,
        val metadata: Any,
    )

    private data class CachedResult(
        val result: UpdateInfo?,
        val timestampMs: Long,
    )

    private enum class Channel {
        STABLE,
        RC,
    }

    private data class CacheKey(
        val apiBaseUrl: String,
        val channel: Channel,
    )

    private val cachedEntries = ConcurrentHashMap<CacheKey, CachedResult>()
    private val pageCache = ConcurrentHashMap<String, CachedPage>()

    internal fun clearCache() {
        cachedEntries.clear()
        pageCache.clear()
    }

    /**
     * Stream a release APK asset to [dest], reporting progress 0..1 via
     * [onProgress]. True on success; false (with the partial file deleted)
     * on any HTTP or I/O failure.
     */
    open suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            val request =
                try {
                    val httpUrl =
                        url.toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
                            ?: run {
                                failDownload(continuation, dest)
                                return@suspendCancellableCoroutine
                            }
                    Request.Builder().url(httpUrl).build()
                } catch (_: IllegalArgumentException) {
                    failDownload(continuation, dest)
                    return@suspendCancellableCoroutine
                }
            val call =
                try {
                    client.newCall(request)
                } catch (_: Exception) {
                    failDownload(continuation, dest)
                    return@suspendCancellableCoroutine
                }
            continuation.invokeOnCancellation {
                call.cancel()
                deletePartial(dest)
            }
            val callback =
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        failDownload(continuation, dest)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        var succeeded = false
                        try {
                            response.use {
                                if (response.isSuccessful) {
                                    val body = response.body
                                    val total = body.contentLength()
                                    dest.outputStream().use { out ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var read = 0L
                                        body.byteStream().use { input ->
                                            while (true) {
                                                val n = input.read(buffer)
                                                if (n == -1) break
                                                out.write(buffer, 0, n)
                                                read += n
                                                if (total > 0) {
                                                    onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                                }
                                            }
                                        }
                                    }
                                    succeeded = true
                                }
                            }
                        } catch (_: Exception) {
                            // Includes response/body close failures; never report success before close.
                        }
                        if (succeeded) {
                            if (continuation.isActive) continuation.resume(true) { _, _, _ -> }
                        } else {
                            failDownload(continuation, dest)
                        }
                    }
                }
            try {
                call.enqueue(callback)
            } catch (_: Exception) {
                failDownload(continuation, dest)
            }
        }

    private fun failDownload(
        continuation: kotlinx.coroutines.CancellableContinuation<Boolean>,
        dest: File,
    ) {
        deletePartial(dest)
        if (continuation.isActive) continuation.resume(false) { _, _, _ -> }
    }

    private fun deletePartial(dest: File) {
        runCatching { dest.delete() }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8192
        const val HTTP_NOT_FOUND = 404
        const val HTTP_NOT_MODIFIED = 304

        const val RELEASE_LIST_PAGE_SIZE = 100
        val NEXT_LINK = Regex("<([^>]+)>;\\s*rel=\"next\"")
        val REPOSITORIES_RELEASES_PATH = Regex("^/repositories/[0-9]+/releases$")
    }
}

/** Parse a GitHub `releases/latest` JSON body into [UpdateInfo], or null. */
fun parseUpdateInfo(json: String): UpdateInfo? =
    runCatching {
        OkHttpProvider.json.decodeFromString<UpdateInfo>(json)
    }.getOrNull()

/** Parse a GitHub `releases` list JSON body into [UpdateInfo]s, or null. */
fun parseReleaseList(json: String): List<UpdateInfo>? =
    runCatching {
        OkHttpProvider.json.decodeFromString<List<UpdateInfo>>(json)
    }.getOrNull()
