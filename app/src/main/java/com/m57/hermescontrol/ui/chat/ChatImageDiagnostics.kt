package com.m57.hermescontrol.ui.chat

import android.util.Log
import coil3.network.HttpException
import com.m57.hermescontrol.BuildConfig

/** #1459: debug-only, allowlisted metadata. Never log URLs, paths, text, or exception messages. */
internal object ChatImageDiagnostics {
    private const val TAG = "ChatImageDiag"

    // Correlation only, not a cryptographic identifier. Never hash content or credential-bearing URLs.
    fun rowKey(id: String): String = id.hashCode().toUInt().toString(16)

    fun source(model: Any): String =
        when {
            model is String && model.startsWith("https:", ignoreCase = true) -> "https"
            model is String && model.startsWith("http:", ignoreCase = true) -> "http"
            model is String && model.startsWith("content:", ignoreCase = true) -> "content"
            model is String && model.startsWith("file:", ignoreCase = true) -> "file"
            model is String && model.startsWith("data:", ignoreCase = true) -> "data"
            else -> "other"
        }

    fun loadLine(
        phase: String,
        model: Any,
        diagnosticId: String?,
        error: Throwable? = null,
    ): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val types = causes.joinToString(">") { it.javaClass.simpleName }.ifEmpty { "none" }
        val httpCode =
            causes
                .filterIsInstance<HttpException>()
                .firstOrNull()
                ?.response
                ?.code
        return "phase=$phase source=${source(model)} row=${diagnosticId ?: "standalone"} " +
            "error=$types http=${httpCode ?: "unknown"}"
    }

    fun load(
        phase: String,
        model: Any,
        diagnosticId: String?,
        error: Throwable? = null,
    ) {
        if (BuildConfig.DEBUG) Log.d(TAG, loadLine(phase, model, diagnosticId, error))
    }

    fun historyLines(
        before: List<ChatMessage>,
        incoming: List<ChatMessage>,
        after: List<ChatMessage>,
        cached: Boolean,
    ): List<String> {
        val stages = listOf("before" to before, "incoming" to incoming, "after" to after)
        val imageRows =
            stages.flatMap { it.second }.filter {
                it.role == MessageRole.USER &&
                    (
                        it.content.contains("@image:") ||
                            it.attachments.orEmpty().any { attachment ->
                                attachment.isImage
                            }
                    )
            }
        val ids = imageRows.flatMap { listOfNotNull(it.id, it.canonicalRestId) }.toSet()
        if (ids.isEmpty()) return emptyList()
        val origin = if (cached) "cache" else "rest"
        return stages.flatMap { (stage, rows) ->
            rows.filter { it.id in ids || it.canonicalRestId in ids }.map { message ->
                val attachments = message.attachments.orEmpty()
                val sources = attachments.joinToString(",") { source(it.uri) }.ifEmpty { "none" }
                "merge=$origin stage=$stage row=${rowKey(message.id)} " +
                    "canonical=${message.canonicalRestId?.let(::rowKey) ?: "none"} " +
                    "refs=${message.content.contains("@image:")} attachments=${attachments.size} sources=$sources"
            }
        } + "merge=$origin imageRowsAfter=${after.count { it.id in ids || it.canonicalRestId in ids }}"
    }

    fun history(
        before: List<ChatMessage>,
        incoming: List<ChatMessage>,
        after: List<ChatMessage>,
        cached: Boolean,
    ) {
        if (BuildConfig.DEBUG) historyLines(before, incoming, after, cached).forEach { Log.d(TAG, it) }
    }
}
