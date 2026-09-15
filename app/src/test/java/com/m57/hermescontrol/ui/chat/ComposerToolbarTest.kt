package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [composerModelLabel] — the label shown in the composer's
 * model/reasoning pill. Only the "custom:" provider marker is dropped; the
 * provider stays so the same model from different providers never collides.
 */
class ComposerToolbarTest {
    @Test
    fun composerModelLabel_keepsProviderToDisambiguateSameModel() {
        assertEquals("openai/gpt-5.5", composerModelLabel("openai/gpt-5.5"))
        assertEquals("copilot/gpt-5.5", composerModelLabel("copilot/gpt-5.5"))
    }

    @Test
    fun composerModelLabel_dropsOnlyTheCustomMarker() {
        assertEquals("my-provider/glm-5.3", composerModelLabel("custom:my-provider/glm-5.3"))
        assertEquals("openrouter/openai/gpt-5.5", composerModelLabel("openrouter/openai/gpt-5.5"))
    }

    @Test
    fun composerModelLabel_leavesModelTagsAndPlainIdsAlone() {
        assertEquals("ollama/llama3:8b", composerModelLabel("ollama/llama3:8b"))
        assertEquals("llama3:8b", composerModelLabel("llama3:8b"))
        assertEquals("gpt-5", composerModelLabel("gpt-5"))
    }

    @Test
    fun composerModelLabel_bareCustomMarker_fallsBackToFullId() {
        assertEquals("custom:/glm-5.3", composerModelLabel("custom:/glm-5.3"))
    }
}
