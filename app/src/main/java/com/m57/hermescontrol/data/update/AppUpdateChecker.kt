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

/**
 * Talks to the GitHub releases API and downloads the release APK (issue
 * #867). Network methods are `open` so tests can fake them; the pure logic
 * ([isNewerVersion], [parseUpdateInfo]) lives top-level for direct unit
 * tests.
 */
open class AppUpdateChecker(
    private val client: OkHttpClient = OkHttpProvider.base,
) {
    /**
     * Fetch the latest release metadata. Returns null when there is no
     * release yet (404) or the body can't be parsed. Throws [IOException]
     * on network failure so the caller can surface a friendly error.
     */
    open suspend fun fetchLatestRelease(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/Hy4ri/hermes-mobile/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string().orEmpty()
                parseUpdateInfo(body)
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
    }
}

/** Parse a GitHub `releases/latest` JSON body into [UpdateInfo], or null. */
fun parseUpdateInfo(json: String): UpdateInfo? =
    runCatching {
        OkHttpProvider.json.decodeFromString<UpdateInfo>(json)
    }.getOrNull()
