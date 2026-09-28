package com.m57.hermescontrol.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.m57.hermescontrol.ui.chat.tool.ToolCatalog

/** Display configuration for a Hermes tool — its name and header icon. */
data class ToolDisplayConfig(
    val name: String,
    /** Material icon for this tool type. */
    val icon: ImageVector = Icons.Filled.Build,
)

/** Icon lookup backed by the single [ToolCatalog] (issue #1326). */
object ToolSchemaRegistry {
    fun getDisplayConfig(toolName: String?): ToolDisplayConfig =
        ToolDisplayConfig(name = toolName ?: "tool", icon = ToolCatalog.definitionFor(toolName).icon)
}
