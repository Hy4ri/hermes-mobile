package com.m57.hermescontrol.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronSaveErrorTest {
    @Test
    fun `missing work maps to prompt field`() {
        val msg = "agent cron jobs require a prompt, skill, or script"
        assertEquals(CronSaveError.Prompt, mapCronSaveError(msg))
    }

    @Test
    fun `invalid schedule keeps first line only`() {
        val msg = "Invalid schedule 'bogus'. Use:\n  - Interval: '30m'\n  - Cron: '0 9 * * *'"
        assertEquals(CronSaveError.Schedule("Invalid schedule 'bogus'"), mapCronSaveError(msg))
    }

    @Test
    fun `other errors stay toasts`() {
        assertNull(mapCronSaveError("boom"))
        assertNull(mapCronSaveError(null))
    }
}
