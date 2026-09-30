package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicBlobTest {
    private val density = 2f

    @Test
    fun `morphing never pushes the interpolated radius out of the envelope`() {
        val blob = MicBlob(n = 12, minRadius = 47.dp, maxRadius = 55.dp, densityPxPerDp = density)
        for (step in 0 until 2_000) {
            blob.update(amplitude = 1f, speedScale = 1.01f)
            if (!blob.radiiInEnvelope()) {
                org.junit.Assert.fail("radius escaped the envelope at step $step")
            }
        }
        assertTrue(true)
    }

    @Test
    fun `idle amplitude still morphs slowly and stays in the envelope`() {
        val blob = MicBlob(n = 11, minRadius = 47.dp, maxRadius = 55.dp, densityPxPerDp = density)
        for (step in 0 until 5_000) {
            blob.update(amplitude = 0f, speedScale = 1.01f)
            assertTrue(blob.radiiInEnvelope())
        }
    }

    @Test
    fun `seed is deterministic`() {
        val a = MicBlob(n = 12, minRadius = 47.dp, maxRadius = 55.dp, densityPxPerDp = density)
        val b = MicBlob(n = 12, minRadius = 47.dp, maxRadius = 55.dp, densityPxPerDp = density)
        for (step in 0 until 500) {
            a.update(amplitude = 0.5f, speedScale = 1.01f)
            b.update(amplitude = 0.5f, speedScale = 1.01f)
        }
        assertArrayEquals(a.radiiSnapshot(), b.radiiSnapshot(), 0.0001f)
    }
}
