package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProviderUsageStatusTest {
    private val json = OkHttpProvider.json
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun provider(extra: String = ""): ModelProvider =
        json.decodeFromString("""{"slug":"anthropic","name":"Anthropic"$extra}""")

    @Test
    fun oldPayloadWithoutUsageOrLimit_decodesAndIsHealthy() {
        val p = provider()
        assertNull(p.usage)
        assertNull(p.limit)
        assertNull(p.usageStatus(now))
    }

    @Test
    fun unknownFutureKeys_doNotThrow() {
        val p =
            provider(""","usage":{"windows":[],"accounts":[{"id":"x"}],"future":1},"limit":{"scope":"account","x":2}""")
        assertTrue(p.usageStatus(now) is ProviderUsageStatus.Limited)
    }

    @Test
    fun activeProviderLimit_isLimitedWithResetTime() {
        val p = provider(""","limit":{"resets_at":"2026-10-14T15:30:00Z","scope":"account"}""")
        val status = p.usageStatus(now) as ProviderUsageStatus.Limited
        assertEquals(Instant.parse("2026-10-14T15:30:00Z"), status.resetsAt)
    }

    @Test
    fun expiredLimit_isIgnored() {
        val p = provider(""","limit":{"resets_at":"2026-10-14T11:00:00Z","scope":"account"}""")
        assertNull(p.usageStatus(now))
    }

    @Test
    fun modelScopedLimit_doesNotLimitWholeProvider() {
        val p = provider(""","limit":{"scope":"models","models":{"opus":"2026-10-14T15:30:00Z"}}""")
        assertNull(p.usageStatus(now))
    }

    @Test
    fun tightestWindowWins_andSpentWindowsIgnoreModelScope() {
        val p =
            provider(
                ""","usage":{"windows":[
                  {"label":"Weekly","used_percent":40.0,"resets_at":"2026-10-20T00:00:00Z"},
                  {"label":"5-hour","used_percent":92.4,"resets_at":"2026-10-14T15:30:00Z"},
                  {"label":"Opus","used_percent":99.0,"scope":"model"}
                ]}""",
            )
        val status = p.usageStatus(now) as ProviderUsageStatus.LowQuota
        assertEquals(8, status.percentLeft)
        assertTrue(status.isCritical)
        assertEquals(Instant.parse("2026-10-14T15:30:00Z"), status.resetsAt)
    }

    @Test
    fun quotaBoundaries() {
        fun left(used: Double) = provider(""","usage":{"windows":[{"used_percent":$used}]}""").usageStatus(now)
        assertNull(left(79.0))
        val twenty = left(80.0) as ProviderUsageStatus.LowQuota
        assertEquals(20, twenty.percentLeft)
        assertTrue(!twenty.isCritical)
        assertTrue((left(90.0) as ProviderUsageStatus.LowQuota).isCritical)
    }

    @Test
    fun malformedTimestamp_degradesToNoTime() {
        val p = provider(""","limit":{"resets_at":"soon","scope":"account"}""")
        assertEquals(ProviderUsageStatus.Limited(null), p.usageStatus(now))
    }
}
