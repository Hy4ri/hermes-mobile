package com.m57.hermescontrol.ui.chat.tool

/** Legacy alias pointing directly to [ToolCatalog.rendererFor]. */
internal object ToolRendererRegistry {
    fun rendererFor(toolName: String): ToolRenderer = ToolCatalog.rendererFor(toolName)
}
