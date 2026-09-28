package com.m57.hermescontrol.ui.chat.tool

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Terminal
import com.m57.hermescontrol.ui.chat.ToolSchemaRegistry
import com.m57.hermescontrol.ui.chat.tool.render.BrowserTypeRenderer
import com.m57.hermescontrol.ui.chat.tool.render.FileEditRenderer
import com.m57.hermescontrol.ui.chat.tool.render.TerminalRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolCatalogTest {
    @Test
    fun `icon and renderer lookups read the same catalog entry`() {
        for ((name, def) in ToolCatalog.entries) {
            assertSame(name, def.icon, ToolSchemaRegistry.getDisplayConfig(name).icon)
            assertSame(name, def.renderer, ToolRendererRegistry.rendererFor(name))
            assertEquals(name, ToolSchemaRegistry.getDisplayConfig(name).name)
        }
    }

    @Test
    fun `tools that drifted now have a real icon`() {
        assertSame(Icons.Filled.Edit, ToolSchemaRegistry.getDisplayConfig("edit_file").icon)
        assertSame(Icons.Filled.Keyboard, ToolSchemaRegistry.getDisplayConfig("browser_fill").icon)
        for (name in listOf("project_list", "project_create", "project_switch")) {
            assertSame(name, Icons.Filled.AccountTree, ToolSchemaRegistry.getDisplayConfig(name).icon)
        }
    }

    @Test
    fun `known tools keep their renderers and icons`() {
        assertSame(TerminalRenderer, ToolRendererRegistry.rendererFor("terminal"))
        assertSame(Icons.Filled.Terminal, ToolSchemaRegistry.getDisplayConfig("terminal").icon)
        assertSame(FileEditRenderer, ToolRendererRegistry.rendererFor("edit_file"))
        assertSame(BrowserTypeRenderer, ToolRendererRegistry.rendererFor("browser_fill"))
        // Icon-only tools still render generically.
        assertSame(GenericToolRenderer, ToolRendererRegistry.rendererFor("clarify"))
    }

    @Test
    fun `unknown tools fall back to build icon and generic renderer`() {
        assertSame(Icons.Filled.Build, ToolSchemaRegistry.getDisplayConfig("mystery").icon)
        assertEquals("mystery", ToolSchemaRegistry.getDisplayConfig("mystery").name)
        assertEquals("tool", ToolSchemaRegistry.getDisplayConfig(null).name)
        assertSame(GenericToolRenderer, ToolRendererRegistry.rendererFor("mystery"))
    }
}
