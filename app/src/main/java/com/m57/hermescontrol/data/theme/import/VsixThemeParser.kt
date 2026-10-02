package com.m57.hermescontrol.data.theme.import

import com.m57.hermescontrol.data.remote.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads a VS Code theme `.vsix` package and extracts its contributed
 * color themes (t_f3c6f528).
 *
 * A VSIX is a ZIP archive. `package.json` lists contributed themes under
 * `contributes.themes[]` with a `path` to a `*-color-theme.json` file;
 * the actual `colors` map lives in those files, not inline in
 * `package.json`. Every contributed file is parsed (JSONC-tolerant, like
 * the desktop `parseVscodeTheme`) and returned as a [ThemeTokenSet] family
 * for [ThemeDefinitionConverter.buildFamily].
 */
class VsixThemeParser(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Download the VSIX from [vsixUrl] and return all contributed themes. */
    suspend fun parseVsix(vsixUrl: String): Result<List<ThemeTokenSet>> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = downloadVsixBytes(vsixUrl)
                Result.success(extractVariants(bytes))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun downloadVsixBytes(vsixUrl: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(vsixUrl)
                    .header("User-Agent", "Hermes-Mobile")
                    .build()
            val response = client.newCall(request).await()
            response.use {
                if (!it.isSuccessful) throw IOException("VSIX download failed: HTTP ${it.code}")
                val bytes = it.body.bytes()
                if (bytes.size > MAX_VSIX_BYTES) throw IOException("VSIX exceeded the size limit")
                if (bytes.isEmpty()) throw IOException("Empty VSIX response body")
                bytes
            }
        }

    private fun extractVariants(bytes: ByteArray): List<ThemeTokenSet> {
        val entries = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryBytes = zis.readBytes()
                    totalBytes += entryBytes.size
                    if (totalBytes > MAX_UNCOMPRESSED_VSIX_BYTES) {
                        throw IOException("VSIX uncompressed content exceeded size limit")
                    }
                    entries[entry.name] = entryBytes
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val packageEntry =
            entries.entries.firstOrNull { (name, _) ->
                name.equals("extension/package.json", ignoreCase = true) ||
                    name.equals("package.json", ignoreCase = true)
            }
        if (packageEntry != null) {
            val content = readCapped(packageEntry.value)
            val contributed = parseContributedThemes(content)
            if (contributed.isNotEmpty()) {
                val variants =
                    contributed.mapNotNull { ref ->
                        readThemeFile(entries, ref)?.let { fileContent ->
                            parseThemeFile(fileContent, ref.label, ref.type)
                        }
                    }
                if (variants.isNotEmpty()) return variants
            }
        }
        // Fallback: scan for any theme JSON files.
        val scanned =
            entries
                .filter { (name, _) -> name.endsWith(".json", ignoreCase = true) }
                .toList()
                .take(MAX_SCAN_FILES)
                .mapNotNull { (name, fileBytes) ->
                    runCatching {
                        parseThemeFile(readCapped(fileBytes), name, "")
                    }.getOrNull()
                }.filter { it.colors.isNotEmpty() }
        if (scanned.isNotEmpty()) return scanned
        throw IOException("No color themes found in VSIX")
    }

    private data class ThemeRef(
        val path: String,
        val label: String,
        val type: String,
    )

    private fun parseContributedThemes(packageJson: String): List<ThemeRef> {
        val obj = json.parseToJsonElement(stripJsonc(packageJson)).jsonObject
        val contributes = obj["contributes"]?.jsonObject ?: return emptyList()
        val themes = contributes["themes"] as? JsonArray ?: return emptyList()
        return themes.mapNotNull { element ->
            if (element !is JsonObject) return@mapNotNull null
            val path = element["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ThemeRef(
                path = path,
                label = element["label"]?.jsonPrimitive?.content.orEmpty(),
                type = element["uiTheme"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    private fun readThemeFile(
        entries: Map<String, ByteArray>,
        ref: ThemeRef,
    ): String? {
        val candidates =
            listOf(
                ref.path,
                ref.path.removePrefix("./"),
                "extension/${ref.path.removePrefix("./")}",
                "extension/${ref.path}",
            ).distinct()
        for (candidate in candidates) {
            val match =
                entries.entries.firstOrNull { (name, _) -> name.equals(candidate, ignoreCase = true) } ?: continue
            return readCapped(match.value)
        }
        return null
    }

    private fun parseThemeFile(
        content: String,
        label: String,
        type: String,
    ): ThemeTokenSet {
        val obj = json.parseToJsonElement(stripJsonc(content)).jsonObject
        val colorsObj = obj["colors"] as? JsonObject
        val colors = mutableMapOf<String, String>()
        colorsObj?.entries?.forEach { (key, value) ->
            runCatching { value.jsonPrimitive.content }.getOrNull()?.let { colors[key] = it }
        }
        val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
        return ThemeTokenSet(
            colors = colors,
            name = name.ifBlank { label },
            label = label.ifBlank { name },
            type = type.ifBlank { obj["type"]?.jsonPrimitive?.content.orEmpty() },
        )
    }

    /**
     * Strip JSONC (line/block comments + trailing commas) like the desktop
     * `parseVscodeTheme`. Naive but sufficient for real-world theme files.
     */
    internal fun stripJsonc(text: String): String =
        text
            .replace(BLOCK_COMMENT_RE, "")
            .replace(LINE_COMMENT_RE, "$1")
            .replace(TRAILING_COMMA_RE, "$1")

    private fun readCapped(bytes: ByteArray): String {
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Theme file exceeded the size limit")
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val MAX_VSIX_BYTES = 8 * 1024 * 1024
        const val MAX_UNCOMPRESSED_VSIX_BYTES = 32 * 1024 * 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_SCAN_FILES = 10
        private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/""")
        private val LINE_COMMENT_RE = Regex("""(^|[^:"'\\])//[^\n\r]*""")
        private val TRAILING_COMMA_RE = Regex(""",(\s*[}\]])""")

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
