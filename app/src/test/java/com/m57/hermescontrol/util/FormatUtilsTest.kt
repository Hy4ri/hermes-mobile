package com.m57.hermescontrol.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {
    @Test
    fun testFormatBytes_bytes() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("500 B", formatBytes(500L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test
    fun testFormatBytes_kilobytes() {
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("2 KB", formatBytes(2048L))
        assertEquals("1023 KB", formatBytes(1024 * 1024 - 1L))
    }

    @Test
    fun testFormatBytes_megabytes() {
        assertEquals("1 MB", formatBytes(1024 * 1024L))
        assertEquals("500 MB", formatBytes(500 * 1024 * 1024L))
    }

    @Test
    fun testFormatBytes_gigabytes() {
        assertEquals("1.0 GB", formatBytes(1024 * 1024 * 1024L))
        assertEquals("2.5 GB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFormatDuration_minutes() {
        assertEquals("0m", formatDuration(0.0))
        assertEquals("0m", formatDuration(45.0))
        assertEquals("1m", formatDuration(60.0))
        assertEquals("59m", formatDuration(3540.0))
    }

    @Test
    fun testFormatDuration_hoursAndMinutes() {
        assertEquals("1h 0m", formatDuration(3600.0))
        assertEquals("1h 1m", formatDuration(3660.0))
        assertEquals("23h 59m", formatDuration(86399.0))
    }

    @Test
    fun testFormatDuration_daysHoursMinutes() {
        assertEquals("1d 0h 0m", formatDuration(86400.0))
        assertEquals("1d 1h 1m", formatDuration(90060.0))
        assertEquals("3d 5h 30m", formatDuration(3 * 86400.0 + 5 * 3600.0 + 30 * 60.0))
    }
}
