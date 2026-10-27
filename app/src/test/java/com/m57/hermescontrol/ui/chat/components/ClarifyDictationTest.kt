package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ClarifyDictationTest {
    @Test
    fun emptyFieldTakesDictation() = assertEquals("hola", appendDictation("", " hola "))

    @Test
    fun typedTextIsKeptAndDictationAppended() =
        assertEquals("ya escrito y dictado", appendDictation("ya escrito ", "y dictado"))

    @Test
    fun blankDictationLeavesFieldUntouched() = assertEquals("algo", appendDictation("algo", "   "))

    @Test
    fun dictationOnlyTouchesItsQuestion() =
        assertEquals(
            mapOf("a" to "uno dos", "b" to "otra"),
            mapOf("a" to "uno", "b" to "otra").withDictation("a", "dos"),
        )
}
