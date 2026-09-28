package com.m57.hermescontrol.ui.chat.tool

import com.m57.hermescontrol.ui.chat.parseToolOutput

/**
 * Bounded memo of parsed tool payloads (issue #1327).
 *
 * The ViewModel [prewarm]s tool rows on a background dispatcher whenever the
 * transcript changes, so [ToolBubble] normally gets a cache hit during
 * composition instead of parsing JSON on the main thread. A miss (e.g. a live
 * event that lands before prewarm finishes) parses synchronously exactly like
 * before, so the rendered result — and its height — never changes.
 *
 * Keyed by the payload itself (not its hash), so a hit is always the view the
 * same inputs would produce.
 */
internal object ToolViewCache {
    private const val MAX_ENTRIES = 512

    private data class Key(
        val content: String,
        val toolName: String?,
        val isRunning: Boolean,
    )

    /** Boxes the result so a null parse (non-JSON content) is cached too. */
    private class Entry(
        val view: ToolView?,
    )

    private val entries =
        object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > MAX_ENTRIES
        }

    fun getOrParse(
        content: String,
        toolName: String?,
        isRunning: Boolean,
    ): ToolView? {
        val key = Key(content, toolName, isRunning)
        synchronized(entries) { entries[key] }?.let { return it.view }
        val view = parseToolOutput(content, toolName, isRunning)
        synchronized(entries) { entries[key] = Entry(view) }
        return view
    }

    fun prewarm(
        content: String,
        toolName: String?,
        isRunning: Boolean,
    ) {
        getOrParse(content, toolName, isRunning)
    }

    internal fun size(): Int = synchronized(entries) { entries.size }

    internal fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
