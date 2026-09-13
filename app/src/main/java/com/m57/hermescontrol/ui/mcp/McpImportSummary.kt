package com.m57.hermescontrol.ui.mcp

object McpImportSummary {
    /**
     * Formats the human-readable summary message reporting the result of a batch MCP JSON import.
     */
    fun formatSummary(
        successCount: Int,
        errors: List<String>,
    ): String {
        val errSummary = errors.joinToString("; ")
        return when {
            errors.isEmpty() -> "Successfully imported $successCount server(s)"
            successCount > 0 -> "Imported $successCount server(s), ${errors.size} failed: $errSummary"
            else -> "Failed to import: $errSummary"
        }
    }
}
