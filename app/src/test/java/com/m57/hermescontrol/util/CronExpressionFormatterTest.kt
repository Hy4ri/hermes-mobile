package com.m57.hermescontrol.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CronExpressionFormatterTest {
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
}
