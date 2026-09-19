package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

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
        .filter { !it.draft && it.apkAsset != null }
        .filter {
            if (!it.prerelease) {
                true
            } else {
                includeReleaseCandidates && isReleaseCandidateVersion(it.tagName)
            }
        }.reduceOrNull { best, candidate ->
            if (isNewerVersion(candidate.tagName, best.tagName)) candidate else best
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
    private val client: OkHttpClient = OkHttpProvider.base,
    private val apiBaseUrl: String = "https://api.github.com/repos/Hy4ri/hermes-mobile",
) {
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
            if (includeReleaseCandidates) {
                val body = get("$apiBaseUrl/releases?per_page=$RELEASE_LIST_PAGE_SIZE") ?: return@withContext null
                selectLatestUpdate(parseReleaseList(body).orEmpty(), includeReleaseCandidates = true)
            } else {
                val body = get("$apiBaseUrl/releases/latest") ?: return@withContext null
                parseUpdateInfo(body)
            }
        }

    /** GET [url] as JSON text, or null on any non-2xx response. */
    private fun get(url: String): String? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string().orEmpty()
        }
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
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
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
                    true
                }
            } catch (e: Exception) {
                dest.delete()
                false
            }
        }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8192

        /** How many recent releases the opt-in RC scan inspects. */
        const val RELEASE_LIST_PAGE_SIZE = 20
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
