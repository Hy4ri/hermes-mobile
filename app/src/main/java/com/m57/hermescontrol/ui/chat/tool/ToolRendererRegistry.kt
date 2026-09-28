package com.m57.hermescontrol.ui.chat.tool

/** Renderer lookup backed by the single [ToolCatalog]. */
internal object ToolRendererRegistry {
    fun rendererFor(toolName: String): ToolRenderer = ToolCatalog.definitionFor(toolName).renderer
}
