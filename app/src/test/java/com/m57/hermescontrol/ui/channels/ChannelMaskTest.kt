package com.m57.hermescontrol.ui.channels

import com.m57.hermescontrol.data.model.EnvVarField
import com.m57.hermescontrol.ui.common.KEY_MASK
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMaskTest {
    private fun field(
        isSet: Boolean,
        redacted: String? = null,
    ) = EnvVarField(
        key = "TOKEN",
        required = true,
        isSet = isSet,
        redactedValue = redacted,
        isPassword = true,
    )

    @Test
    fun `set secret renders only the fixed mask`() {
        assertEquals(KEY_MASK, maskedChannelField(field(isSet = true, redacted = "abcd...wxyz")))
    }

    @Test
    fun `unset secret has no mask`() {
        assertEquals(null, maskedChannelField(field(isSet = false)))
    }
}
