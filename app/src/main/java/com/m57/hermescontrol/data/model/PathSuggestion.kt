package com.m57.hermescontrol.data.model

/**
 * A single path completion returned by the gateway's `complete.path` RPC
 * (issue #536). Mirrors the desktop `ContextSuggestion` shape.
 *
 * @param text The token to insert into the composer (e.g. `@file:src/main.kt`).
 * @param display Short label shown in the dropdown (basename or keyword).
 * @param meta Optional secondary text (parent dir, "dir", or a keyword hint).
 * @param isDirectory Whether the entry is a directory (used to render a hint).
 */
data class PathSuggestion(
    val text: String,
    val display: String,
    val meta: String? = null,
    val isDirectory: Boolean = false,
) {
    companion object {
        /**
         * Build a [PathSuggestion] from a raw `complete.path` item map.
         * Returns `null` when the item is missing a usable `text` field.
         */
        fun fromMap(map: Map<*, *>): PathSuggestion? {
            val text = map["text"] as? String ?: return null
            if (text.isBlank()) return null
            val display = (map["display"] as? String).orEmpty()
            val meta = map["meta"] as? String
            val isDirectory = text.endsWith("/") || meta == "dir"
            return PathSuggestion(
                text = text,
                display = display.ifBlank { text },
                meta = meta,
                isDirectory = isDirectory,
            )
        }
    }
}
