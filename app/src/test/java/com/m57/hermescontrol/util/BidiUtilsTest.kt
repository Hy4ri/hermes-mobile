package com.m57.hermescontrol.util

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiUtilsTest {
    @Test
    fun testIsRtlText_pureArabic() {
        assertTrue(BidiUtils.isRtlText("مرحبا بك في تطبيق هيرمس"))
    }

    @Test
    fun testIsRtlText_pureEnglish() {
        assertFalse(BidiUtils.isRtlText("Hello from Hermes Control"))
    }

    @Test
    fun testIsRtlText_mixedArabicWithEnglishWords() {
        val text = "هذا النص يحتوي على Android و Compose داخل فقرة عربية"
        assertTrue(BidiUtils.isRtlText(text))
    }

    @Test
    fun testIsRtlText_arabicStartingWithEnglishTerm() {
        val text = "Android هو نظام تشغيل رائع ومميز جدا"
        assertTrue(BidiUtils.isRtlText(text))
    }

    @Test
    fun testIsRtlText_englishSentenceWithArabicWord() {
        val text = "The Arabic word for welcome is مرحبا in most dialects"
        assertFalse(BidiUtils.isRtlText(text))
    }

    @Test
    fun testIsRtlText_neutralOnly() {
        assertFalse(BidiUtils.isRtlText(""))
        assertFalse(BidiUtils.isRtlText("   "))
        assertFalse(BidiUtils.isRtlText("12345"))
        assertFalse(BidiUtils.isRtlText("🚀✨🔥"))
        assertFalse(BidiUtils.isRtlText("/status"))
    }

    @Test
    fun testIsRtlText_neutralPrefixWithArabic() {
        assertTrue(BidiUtils.isRtlText("🚀 مرحبا يا صديقي"))
        assertTrue(BidiUtils.isRtlText("- [x] مهمة جديدة مع Compose"))
    }

    @Test
    fun testDetectTextDirection() {
        assertEquals(BidiUtils.BidiDirection.RTL, BidiUtils.detectTextDirection("أهلا"))
        assertEquals(BidiUtils.BidiDirection.LTR, BidiUtils.detectTextDirection("Hello"))
        assertEquals(BidiUtils.BidiDirection.NEUTRAL, BidiUtils.detectTextDirection("123 🚀"))
    }

    @Test
    fun testResolveLayoutDirection() {
        assertEquals(LayoutDirection.Rtl, BidiUtils.resolveLayoutDirection("صباح الخير"))
        assertEquals(LayoutDirection.Ltr, BidiUtils.resolveLayoutDirection("Good morning"))
        assertEquals(LayoutDirection.Ltr, BidiUtils.resolveLayoutDirection("123", fallback = LayoutDirection.Ltr))
        assertEquals(LayoutDirection.Rtl, BidiUtils.resolveLayoutDirection("123", fallback = LayoutDirection.Rtl))
    }

    @Test
    fun testWrapLtrIsolate() {
        assertEquals("${BidiUtils.LRI}Android${BidiUtils.PDI}", BidiUtils.wrapLtrIsolate("Android"))
        assertEquals("${BidiUtils.LRI}val x = 1${BidiUtils.PDI}", BidiUtils.wrapLtrIsolate("val x = 1"))
        // RTL snippet should not be wrapped
        assertEquals("مرحبا", BidiUtils.wrapLtrIsolate("مرحبا"))
    }

    @Test
    fun testAnchorTrailingRtl() {
        val rtlWithEmoji = "مرحبا بكم 🚀"
        assertEquals("مرحبا بكم 🚀${BidiUtils.RLM}", BidiUtils.anchorTrailingRtl(rtlWithEmoji))

        // Does not append duplicate RLM
        val alreadyAnchored = "مرحبا بكم 🚀${BidiUtils.RLM}"
        assertEquals(alreadyAnchored, BidiUtils.anchorTrailingRtl(alreadyAnchored))

        // LTR text remains unmodified
        val ltrWithEmoji = "Welcome to Hermes 🚀"
        assertEquals(ltrWithEmoji, BidiUtils.anchorTrailingRtl(ltrWithEmoji))
    }
}
