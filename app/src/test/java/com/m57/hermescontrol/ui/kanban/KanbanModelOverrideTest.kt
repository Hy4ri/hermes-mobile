package com.m57.hermescontrol.ui.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanModelOverrideTest {
    @Test
    fun testEmptyOverrideIsInherited() {
        val override = KanbanModelOverride.EMPTY
        assertTrue(override.isInherited)
        assertEquals("Inherit profile", override.displayLabel("Inherit profile"))

        val patch = override.toUpdateTaskBody()
        assertNull(patch.modelOverride)
        assertNull(patch.providerOverride)
        assertTrue(patch.clearModelOverride)
        assertNull(patch.reasoningEffort)
        assertTrue(patch.clearReasoningEffort)
    }

    @Test
    fun testCustomModelAndEffortPatch() {
        val override =
            KanbanModelOverride(
                model = "claude-3-5-sonnet",
                provider = "anthropic",
                effort = "high",
            )
        assertFalse(override.isInherited)
        assertEquals("anthropic: claude-3-5-sonnet · High", override.displayLabel())

        val patch = override.toUpdateTaskBody()
        assertEquals("claude-3-5-sonnet", patch.modelOverride)
        assertEquals("anthropic", patch.providerOverride)
        assertFalse(patch.clearModelOverride)
        assertEquals("high", patch.reasoningEffort)
        assertFalse(patch.clearReasoningEffort)
    }

    @Test
    fun testExplicitNoneReasoningEffort() {
        val override =
            KanbanModelOverride(
                model = "gpt-4o",
                provider = "openai",
                effort = "none",
            )
        assertEquals("openai: gpt-4o · None", override.displayLabel())

        val patch = override.toUpdateTaskBody()
        assertEquals("none", patch.reasoningEffort)
        assertFalse(patch.clearReasoningEffort)
    }

    @Test
    fun testModelClearedLeavesEffortIntact() {
        val override = KanbanModelOverride(model = "", provider = "", effort = "low")
        val patch = override.toUpdateTaskBody()
        assertTrue(patch.clearModelOverride)
        assertEquals("low", patch.reasoningEffort)
        assertFalse(patch.clearReasoningEffort)
    }

    @Test
    fun testFullReasoningScaleAndCapabilities() {
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            supportedKanbanReasoningEfforts(supportsReasoning = true, canDisableReasoning = true),
        )
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            supportedKanbanReasoningEfforts(supportsReasoning = true, canDisableReasoning = false),
        )
        assertEquals(
            listOf(""),
            supportedKanbanReasoningEfforts(supportsReasoning = false, canDisableReasoning = false),
        )
    }
}
