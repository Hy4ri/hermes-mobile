package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.model.DiskPressureStatus
import com.m57.hermescontrol.data.model.MemoryPressureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureBannerTest {
    private fun mem(
        pressure: String? = null,
        suspectedOom: Boolean? = null,
    ) = MemoryPressureStatus(
        pressure = pressure,
        last_boot_suspected_oom = suspectedOom,
    )

    private fun disk(pressure: String? = null) = DiskPressureStatus(pressure = pressure)

    @Test
    fun noTrigger_whenOkUnknownOrNull() {
        assertNull(selectPressureTrigger(null, null))
        assertNull(selectPressureTrigger(mem("ok"), disk("ok")))
        assertNull(selectPressureTrigger(mem("unknown"), disk("unknown")))
        assertNull(selectPressureTrigger(mem(), disk()))
    }

    @Test
    fun diskCritical_outranksEverything() {
        assertEquals(
            PressureTrigger.DISK_CRITICAL,
            selectPressureTrigger(mem("critical"), disk("critical")),
        )
        assertEquals(
            PressureTrigger.DISK_CRITICAL,
            selectPressureTrigger(mem("critical", suspectedOom = true), disk("critical")),
        )
        assertEquals(
            PressureTrigger.DISK_CRITICAL,
            selectPressureTrigger(mem("elevated"), disk("critical")),
        )
    }

    @Test
    fun memoryCritical_outranksOomAndElevated() {
        assertEquals(
            PressureTrigger.MEMORY_CRITICAL,
            selectPressureTrigger(mem("critical", suspectedOom = true), disk("elevated")),
        )
        assertEquals(
            PressureTrigger.MEMORY_CRITICAL,
            selectPressureTrigger(mem("critical"), disk("elevated")),
        )
    }

    @Test
    fun oomRestart_shownEvenWhenPressureOk() {
        assertEquals(
            PressureTrigger.OOM_RESTART,
            selectPressureTrigger(mem("ok", suspectedOom = true), disk("ok")),
        )
        assertEquals(
            PressureTrigger.OOM_RESTART,
            selectPressureTrigger(mem("unknown", suspectedOom = true), disk("unknown")),
        )
    }

    @Test
    fun oomRestart_outranksElevatedButNotCritical() {
        assertEquals(
            PressureTrigger.OOM_RESTART,
            selectPressureTrigger(mem("elevated", suspectedOom = true), disk("elevated")),
        )
        assertEquals(
            PressureTrigger.MEMORY_CRITICAL,
            selectPressureTrigger(mem("critical", suspectedOom = true), disk("elevated")),
        )
        assertEquals(
            PressureTrigger.DISK_CRITICAL,
            selectPressureTrigger(mem("elevated", suspectedOom = true), disk("critical")),
        )
    }

    @Test
    fun diskElevated_outranksMemoryElevated() {
        assertEquals(
            PressureTrigger.DISK_ELEVATED,
            selectPressureTrigger(mem("elevated"), disk("elevated")),
        )
    }

    @Test
    fun memoryElevated_shownWhenOnlyTrigger() {
        assertEquals(
            PressureTrigger.MEMORY_ELEVATED,
            selectPressureTrigger(mem("elevated"), disk("ok")),
        )
        assertEquals(
            PressureTrigger.DISK_ELEVATED,
            selectPressureTrigger(mem("ok"), disk("elevated")),
        )
    }

    @Test
    fun oomFlagFalse_doesNotTrigger() {
        assertNull(selectPressureTrigger(mem("ok", suspectedOom = false), disk("ok")))
    }
}
