package com.m57.hermescontrol.ui.keys

import com.m57.hermescontrol.data.model.EnvVarConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMaskTest {
    @Test
    fun `masked value never includes gateway prefix or suffix`() {
        val config = EnvVarConfig(isSet = true, redactedValue = "abcd...wxyz", isPassword = true)
        assertEquals(KEY_MASK, maskedKeyValue(config))
    }

    @Test
    fun `unset key has no mask`() {
        assertEquals(null, maskedKeyValue(EnvVarConfig(isSet = false, isPassword = true)))
    }
}
