package com.m57.hermescontrol.ui.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavVoiceRecorderTest {
    @Test
    fun createWavHeader_describes16KhzMonoPcm() {
        val dataSize = 32_000
        val header = WavVoiceRecorder.createWavHeader(dataSize)
        val littleEndian = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(36 + dataSize, littleEndian.getInt(4))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(1, littleEndian.getShort(20).toInt())
        assertEquals(1, littleEndian.getShort(22).toInt())
        assertEquals(16_000, littleEndian.getInt(24))
        assertEquals(32_000, littleEndian.getInt(28))
        assertEquals(16, littleEndian.getShort(34).toInt())
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(dataSize, littleEndian.getInt(40))
    }
}
