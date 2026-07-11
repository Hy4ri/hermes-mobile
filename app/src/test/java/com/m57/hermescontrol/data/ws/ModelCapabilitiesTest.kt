package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device mirror of `hermes_cli.models.model_supports_fast_mode`.
 * Keep these expectations in lock-step with the backend predicate.
 */
class ModelCapabilitiesTest {
    @Test
    fun `openai flagships support fast`() {
        assertTrue(ModelCapabilities.supportsFastMode("gpt-4o"))
        assertTrue(ModelCapabilities.supportsFastMode("gpt-5"))
        assertTrue(ModelCapabilities.supportsFastMode("gpt-5.1-mini"))
        assertTrue(ModelCapabilities.supportsFastMode("o3"))
        assertTrue(ModelCapabilities.supportsFastMode("o4-mini"))
        assertTrue(ModelCapabilities.supportsFastMode("o1"))
    }

    @Test
    fun `openai codex models do NOT support fast`() {
        assertFalse(ModelCapabilities.supportsFastMode("gpt-5-codex"))
        assertFalse(ModelCapabilities.supportsFastMode("o4-codex"))
    }

    @Test
    fun `anthropic opus-4-6 supports fast`() {
        assertTrue(ModelCapabilities.supportsFastMode("claude-opus-4-6"))
        assertTrue(ModelCapabilities.supportsFastMode("anthropic/claude-opus-4.6"))
        assertTrue(ModelCapabilities.supportsFastMode("claude-opus-4-6-202509" ))
    }

    @Test
    fun `other anthropic models do NOT support fast`() {
        assertFalse(ModelCapabilities.supportsFastMode("claude-opus-4-7"))
        assertFalse(ModelCapabilities.supportsFastMode("claude-sonnet-4-6"))
        assertFalse(ModelCapabilities.supportsFastMode("claude-haiku-4-5"))
    }

    @Test
    fun `vendor prefixed and unknown models handled`() {
        assertFalse(ModelCapabilities.supportsFastMode("openrouter/gpt-4o"))
        assertFalse(ModelCapabilities.supportsFastMode("gemini-2.5-pro"))
        assertFalse(ModelCapabilities.supportsFastMode("deepseek/deepseek-reasoner"))
        assertFalse(ModelCapabilities.supportsFastMode(null))
        assertFalse(ModelCapabilities.supportsFastMode(""))
    }
}
