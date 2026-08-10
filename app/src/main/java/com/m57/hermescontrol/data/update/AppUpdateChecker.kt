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
 * True when [latest] is strictly newer than [current]. Numeric dot-segment
 * comparison ("1.21.0" > "1.2"). Non-numeric suffixes (e.g. "1.0-dev")
 * compare as 0; an unparseable version never claims an update exists.
 */
fun isNewerVersion(
    latest: String,
    current: String,
): Boolean {
    val a = versionSegments(latest) ?: return false
    val b = versionSegments(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionSegments(version: String): List<Int>? {
    val cleaned = normalizedVersion(version)
    if (cleaned.isBlank()) return null
    return cleaned.split('.').map { segment ->
        // Leading numeric portion of each segment: "0-dev" → 0, "21" → 21.
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
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
                val body = response.body?.string().orEmpty()
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
                    val body = response.body ?: return@withContext false
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
