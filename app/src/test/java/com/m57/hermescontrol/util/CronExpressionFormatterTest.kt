package com.m57.hermescontrol.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CronExpressionFormatterTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun testCronToHumanReadable() {
        assertEquals("At 09:00 and 21:00 every day", CronExpressionFormatter.cronToHumanReadable("0 9,21 * * *"))
        assertEquals("At 08:30 Monday through Friday", CronExpressionFormatter.cronToHumanReadable("30 8 * * 1-5"))
        assertEquals("Every day at midnight", CronExpressionFormatter.cronToHumanReadable("0 0 * * *"))
        assertEquals("Every 15 minutes", CronExpressionFormatter.cronToHumanReadable("*/15 * * * *"))
        assertEquals("Every Monday at 09:00", CronExpressionFormatter.cronToHumanReadable("0 9 * * 1"))
        assertEquals("Every Sunday at 06:00", CronExpressionFormatter.cronToHumanReadable("0 6 * * 0"))
        assertEquals("On the 1st of every month at 12:00", CronExpressionFormatter.cronToHumanReadable("0 12 1 * *"))
        assertEquals("On January 1st at midnight", CronExpressionFormatter.cronToHumanReadable("0 0 1 1 *"))
        assertEquals("Every 2 hours", CronExpressionFormatter.cronToHumanReadable("0 */2 * * *"))
    }

    @Test
    fun testMalformedCron() {
        assertEquals("invalid cron string", CronExpressionFormatter.cronToHumanReadable("invalid cron string"))
        assertEquals("0 9 * *", CronExpressionFormatter.cronToHumanReadable("0 9 * *"))
    }

    @Test
    fun testExceptionFallback() {
        // This will cause an IndexOutOfBoundsException because 15 is out of bounds for monthNames
        assertEquals("Raw: 0 12 1 15 *", CronExpressionFormatter.cronToHumanReadable("0 12 1 15 *"))

        // This will cause an IndexOutOfBoundsException because 8 is out of bounds for dowNames
        assertEquals("Raw: 0 9 * * 8", CronExpressionFormatter.cronToHumanReadable("0 9 * * 8"))
    }
}
