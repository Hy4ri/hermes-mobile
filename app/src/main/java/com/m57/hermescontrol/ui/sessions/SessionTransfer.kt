package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.local.DataScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Largest export Mobile reads into memory or writes out. Matches the backend's import cap. */
internal const val TRANSFER_MAX_BYTES = 25L * 1024 * 1024

enum class TransferKind { SHARE_TEXT, EXPORT_JSON }

/**
 * An export waiting on the user. [owner] is the server and profile that were active when
 * the user chose the action, so a later scope change cannot redirect the request.
 */
data class PendingTransfer(
    val sessionId: String,
    val title: String?,
    val kind: TransferKind,
    val owner: DataScope,
)

/** The privacy warning was accepted; the screen must open the file picker. */
data class ExportPickerRequest(
    val transfer: PendingTransfer,
    val suggestedName: String,
)

/** Readable text ready for the system share chooser. */
data class ShareTranscriptRequest(
    val id: Long,
    val title: String,
    val text: String,
)

class TransferTooLargeException : IOException("Export is larger than the 25 MB limit")

/** Copies [input] to [output] and fails once more than [limit] bytes were read. Returns the byte count. */
internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long = TRANSFER_MAX_BYTES,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw TransferTooLargeException()
        output.write(buffer, 0, read)
    }
    output.flush()
    return total
}

private const val DEFAULT_BUFFER = 16 * 1024

/** File name for a JSON export: a safe slug of the title plus the session id. */
internal fun exportFileName(
    title: String?,
    sessionId: String,
): String {
    val slug =
        title
            .orEmpty()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(40)
    val id = sessionId.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(24)
    val base = listOf("hermes", slug, id).filter { it.isNotEmpty() }.joinToString("-")
    return "$base.json"
}

private val transferJson = Json { ignoreUnknownKeys = true }

/**
 * Turns the backend export into readable text. Only live user and assistant turns with
 * visible text are kept. Tool payloads, reasoning, compacted history, and inactive rows
 * stay out. Returns null when the payload is not an export or holds no readable turn.
 */
internal fun renderTranscript(
    exportJson: String,
    fallbackTitle: String? = null,
): String? {
    val root = runCatching { transferJson.parseToJsonElement(exportJson) as? JsonObject }.getOrNull() ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    val title = (root["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val turns =
        messages.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (row.flag("active") == false || row.flag("compacted") == true) return@mapNotNull null
            if (!row["display_kind"].isNullOrJsonNull()) return@mapNotNull null
            val role = (row["role"] as? JsonPrimitive)?.contentOrNull
            val label =
                when (role) {
                    "user" -> "You"
                    "assistant" -> "Hermes"
                    else -> return@mapNotNull null
                }
            val text =
                row["display_content"]
                    .visibleText()
                    .ifBlank { row["content"].visibleText() }
                    .lines()
                    .filterNot { it.trimStart().startsWith("@image:") }
                    .joinToString("\n")
                    .trim()
            if (text.isEmpty()) null else "$label:\n$text"
        }
    if (turns.isEmpty()) return null
    return buildString {
        if (!title.isNullOrBlank()) append(title).append("\n\n")
        append(turns.joinToString("\n\n"))
        append('\n')
    }
}

private fun JsonObject.flag(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.booleanOrNull ?: value.contentOrNull?.let { it == "1" || it.equals("true", true) }
}

private fun JsonElement?.isNullOrJsonNull(): Boolean =
    this == null || (this is JsonPrimitive && this.contentOrNull == null)

/** Plain text of a content value: a string, or the text parts of a multimodal array. */
private fun JsonElement?.visibleText(): String =
    when (this) {
        is JsonPrimitive -> {
            contentOrNull.orEmpty()
        }

        is JsonArray -> {
            mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.joinToString("\n")
        }

        else -> {
            ""
        }
    }
