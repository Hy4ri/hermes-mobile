package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun estimate_emptyOrNull_returnsZero() {
        assertEquals(0, TokenEstimator.estimate(null))
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun estimate_asciiText_usesByteDiv4() {
        // "test" is 4 bytes -> (4 + 3) / 4 = 1 token
        assertEquals(1, TokenEstimator.estimate("test"))
        // 8 chars -> (8 + 3) / 4 = 2 tokens
        assertEquals(2, TokenEstimator.estimate("testtest"))
    }

    @Test
    fun estimate_cjkText_countsDenseCharacters() {
        // 3 CJK ideographs -> 3 tokens
        assertEquals(3, TokenEstimator.estimate("你好世界".substring(0, 3)))
        // 4 Korean Hangul -> 4 tokens
        assertEquals(4, TokenEstimator.estimate("안녕하세요".substring(0, 4)))
    }

    @Test
    fun formatTokenCount_formatsCompactUnits() {
        assertEquals("42", TokenEstimator.formatTokenCount(42))
        assertEquals("999", TokenEstimator.formatTokenCount(999))
        assertEquals("1,500", TokenEstimator.formatTokenCount(1500))
        assertEquals("12.5k", TokenEstimator.formatTokenCount(12500))
        assertEquals("1.2M", TokenEstimator.formatTokenCount(1200000))
    }

    @Test
    fun formatTps_roundsProperly() {
        assertEquals("42", TokenEstimator.formatTps(41.6))
        assertEquals("50", TokenEstimator.formatTps(50.2))
    }
}
