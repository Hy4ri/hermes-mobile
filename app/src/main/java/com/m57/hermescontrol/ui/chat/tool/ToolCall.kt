package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One normalized tool invocation, as seen by the display engine.
 *
 * [args] and [result] are the payloads coerced to records (stringified JSON
 * re-parsed, non-objects dropped); [rawArgs]/[rawResult] keep the original
 * elements for renderers that need arrays, scalars, or wrapper unwrapping.
 */
internal data class ToolCall(
    val name: String,
    val args: JsonObject?,
    val result: JsonObject?,
    val rawArgs: JsonElement?,
    val rawResult: JsonElement?,
) {
    companion object {
        fun of(
            name: String,
            rawArgs: JsonElement?,
            rawResult: JsonElement?,
        ): ToolCall =
            ToolCall(
                name = name,
                args = ToolJson.parseMaybeObject(rawArgs),
                result = ToolJson.parseMaybeObject(rawResult),
                rawArgs = rawArgs,
                rawResult = rawResult,
            )
    }
}
