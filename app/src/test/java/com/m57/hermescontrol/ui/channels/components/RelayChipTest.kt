package com.m57.hermescontrol.ui.channels.components

import com.m57.hermescontrol.data.model.MessagingPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #461: the relay-exclusive rule turns a platform's direct adapter off at every gateway start,
 * so `state = "disabled"` is not a user setting. The chip must name the relay instead, and the
 * platform the shared bot carries must stay identifiable when it is not switched off.
 */
class RelayChipTest {
    private fun platform(
        disabledByRelay: Boolean = false,
        relayFronted: Boolean = false,
        relayExclusive: Boolean = false,
    ) = MessagingPlatform(
        id = "telegram",
        name = "Telegram",
        enabled = false,
        configured = true,
        state = "disabled",
        disabledByRelay = disabledByRelay,
        relayFronted = relayFronted,
        relayExclusive = relayExclusive,
    )

    @Test
    fun `exclusive relay wins over the managed and fronted flags`() {
        val chip = relayChipFor(platform(disabledByRelay = true, relayFronted = true, relayExclusive = true))

        assertEquals(RelayChip.VIA_RELAY, chip)
    }

    @Test
    fun `disabled by relay without exclusivity is managed by the relay`() {
        val chip = relayChipFor(platform(disabledByRelay = true, relayFronted = true))

        assertEquals(RelayChip.MANAGED_BY_RELAY, chip)
    }

    @Test
    fun `relay fronted platform keeps its own state and adds the shared bot chip`() {
        val chip = relayChipFor(platform(relayFronted = true))

        assertEquals(RelayChip.SHARED_BOT_ONLY, chip)
    }

    @Test
    fun `standalone platform has no relay chip`() {
        val chip = relayChipFor(platform())

        assertEquals(RelayChip.NONE, chip)
    }
}
