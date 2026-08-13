package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.SchemaField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One renderable row of the config form: a schema-driven field or an
 * uncovered ("Other") config path. All rows are FLAT dot-paths — the nested
 * config response is flattened once on load so the form does O(1) lookups.
 */
data class ConfigRow(
    val key: String,
    val field: SchemaField?,
    val value: JsonElement?,
) {
    /** Human label: last path segment, underscores → spaces. */
    val label: String =
        key.split(".").last().replace("_", " ").replaceFirstChar { it.uppercase() }

    /** Search/display text of the current value. */
    val valueText: String = value?.let(::jsonText) ?: ""

    val category: String = if (field == null) "Other" else field.category ?: "general"

    val isUncovered: Boolean = field == null
}

/** Flatten a nested config map into dot-path → leaf-value pairs. */
fun flattenConfig(nested: Map<String, JsonElement>): Map<String, JsonElement> {
    val flat = mutableMapOf<String, JsonElement>()

    fun walk(
        prefix: String,
        map: Map<String, JsonElement>,
    ) {
        for ((key, value) in map) {
            val dotPath = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject) {
                walk(dotPath, value)
            } else {
                flat[dotPath] = value
            }
        }
    }
    walk("", nested)
    return flat
}

/** True when [dotPath] is a schema key or lives under one (ancestor-or-self). */
fun isCoveredBySchema(
    dotPath: String,
    schemaKeys: Set<String>,
): Boolean = schemaKeys.any { dotPath == it || dotPath.startsWith("$it.") }

/** Flat dot-paths present in config but not covered by the schema (sorted). */
fun collectUncoveredPaths(
    flatValues: Map<String, JsonElement>,
    schemaKeys: Set<String>,
): List<String> =
    flatValues.keys
        .filterNot { isCoveredBySchema(it, schemaKeys) }
        .sorted()

/**
 * Match a row against a search query. Covers the key, human label, schema
 * description, category, the CURRENT VALUE, and every select option — so
 * searching "gpt-4o", "daytona" or "kittentts" finds the right field even
 * though none of them appear in a key or description.
 */
fun rowMatchesQuery(
    row: ConfigRow,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase()
    if (row.key.lowercase().contains(q)) return true
    if (row.label.lowercase().contains(q)) return true
    row.field?.let { field ->
        if (field.description?.lowercase()?.contains(q) == true) return true
        if (field.category?.lowercase()?.contains(q) == true) return true
        field.options?.let { options ->
            if (options.any { it.lowercase().contains(q) }) return true
        }
    }
    return row.valueText.lowercase().contains(q)
}

/** Stable text form of a JSON value for display and search. */
fun jsonText(value: JsonElement): String =
    when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.toString()
        is JsonObject -> value.toString()
    }
