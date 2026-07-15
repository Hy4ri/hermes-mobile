package com.m57.hermescontrol.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocaleContextWrapperTest {
    @Test
    fun systemReturnsDeviceDefault() {
        assertEquals(Locale.getDefault(), LocaleContextWrapper.localeForCode("system"))
    }

    @Test
    fun emptyReturnsDeviceDefault() {
        assertEquals(Locale.getDefault(), LocaleContextWrapper.localeForCode(""))
    }

    @Test
    fun bareLanguageCode() {
        val locale = LocaleContextWrapper.localeForCode("ko")
        assertEquals("ko", locale.language)
        assertEquals("", locale.country)
    }

    @Test
    fun regionSeparator_r() {
        val locale = LocaleContextWrapper.localeForCode("zh-rCN")
        assertEquals("zh", locale.language)
        assertEquals("CN", locale.country)
    }

    @Test
    fun regionSeparator_hyphen() {
        val locale = LocaleContextWrapper.localeForCode("pt-BR")
        assertEquals("pt", locale.language)
        assertEquals("BR", locale.country)
    }

    @Test
    fun regionSeparator_underscore() {
        val locale = LocaleContextWrapper.localeForCode("en_US")
        assertEquals("en", locale.language)
        assertEquals("US", locale.country)
    }
}
