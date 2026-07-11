package com.m57.hermescontrol.data.ws

/**
 * On-device mirror of the backend's fast-mode eligibility logic.
 *
 * The gateway's `tui_gateway/server.py` `config.set(key="fast")` handler rejects
 * the toggle with `4002 "fast mode is not available for this model"` when the
 * running model doesn't support it (see `hermes_cli.models.model_supports_fast_mode`).
 * The mobile currently fires that RPC fire-and-forget and swallows the error, so the
 * chip would flip "on" even when the server silently rejected it — a lie-state.
 *
 * Until the backend exposes a capability flag over the wire, we mirror the same
 * predicate here so the UI can grey-out Fast before the user ever taps it. Keep this
 * in lock-step with `hermes_cli/models.py`:
 *   - OpenAI flagships (gpt-*, o1/o3/o4*, NOT *codex*) -> Priority Processing
 *   - Anthropic claude-opus-4-6 only -> Anthropic Fast Mode (speed=fast)
 */
object ModelCapabilities {
    /** Whether Hermes should expose the Fast toggle for [modelId]. */
    fun supportsFastMode(modelId: String?): Boolean {
        val raw = stripVendorPrefix(modelId) ?: return false
        return isAnthropicFastModel(raw) || isOpenAiFastModel(raw)
    }

    private fun stripVendorPrefix(modelId: String?): String? {
        val raw = modelId?.trim()?.lowercase() ?: return null
        if (raw.isEmpty()) return null
        return if ("/" in raw) raw.substringAfter("/") else raw
    }

    private fun isOpenAiFastModel(modelId: String): Boolean {
        val base = modelId.split(":")[0]
        if ("codex" in base) return false
        return base.startsWith("gpt-") ||
            base.startsWith("o1") ||
            base.startsWith("o3") ||
            base.startsWith("o4")
    }

    private fun isAnthropicFastModel(modelId: String): Boolean {
        val base = modelId.split(":")[0]
        if (!base.startsWith("claude-")) return false
        return "opus-4-6" in base || "opus-4.6" in base
    }
}
