package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [displayModelName] — the short label shown in the composer's
 * model/reasoning pill. Only the provider path is dropped; tags are kept.
 */
class ComposerToolbarTest {
    @Test
    fun displayModelName_dropsProviderPath() {
        assertEquals("glm-5.3", displayModelName("custom:my-provider/glm-5.3"))
        assertEquals("gpt-5", displayModelName("openai/gpt-5"))
    }

    @Test
    fun displayModelName_keepsTagsAndPlainIds() {
        assertEquals("llama-3:free", displayModelName("meta-llama/llama-3:free"))
        assertEquals("llama3:8b", displayModelName("llama3:8b"))
        assertEquals("gpt-5", displayModelName("gpt-5"))
    }

    @Test
    fun displayModelName_trailingSlash_fallsBackToFullId() {
        assertEquals("provider/", displayModelName("provider/"))
    }
}
