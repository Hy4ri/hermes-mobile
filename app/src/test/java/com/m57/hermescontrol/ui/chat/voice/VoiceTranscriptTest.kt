package com.m57.hermescontrol.ui.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptTest {
    @Test
    fun mergeVoiceTranscript_usesTranscriptForEmptyComposer() {
        assertEquals("Hello Cassy", mergeVoiceTranscript("", "  Hello Cassy  "))
    }

    @Test
    fun mergeVoiceTranscript_appendsToExistingComposerWithSingleSpace() {
        assertEquals("Please inspect this repository", mergeVoiceTranscript("Please inspect ", " this repository"))
    }

    @Test
    fun mergeVoiceTranscript_keepsExistingTextForBlankResult() {
        assertEquals("Keep this", mergeVoiceTranscript("Keep this", "   "))
    }
}
