package org.jellyfin.mobile.player.mining

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SentencePcmClipTest {
    @Test
    fun `raw Jellyfin PCM becomes a complete playable WAV with only the requested sentence`() {
        val duration = 1500L
        val sampleCount = (duration * SentenceWaveClip.PCM_SAMPLE_RATE / 1000 * 2).toInt()
        val raw = ByteArray(sampleCount + 2000) { (it % 127).toByte() }
        val input = raw.inputStream()
        val wave = SentenceWaveClip.readPcm16(input, duration)
        assertEquals("RIFF", wave.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wave.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(24000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(sampleCount, header.getInt(40))
        assertArrayEquals(raw.copyOf(sampleCount), wave.copyOfRange(44, wave.size))
        assertEquals(2000, input.available())
    }

    @Test
    fun `truncated raw audio and excessive durations never become card media`() {
        assertThrows(IllegalArgumentException::class.java) { SentenceWaveClip.readPcm16(ByteArray(100).inputStream(), 1000) }
        assertThrows(IllegalArgumentException::class.java) { SentenceWaveClip.readPcm16(ByteArray(0).inputStream(), 30_001) }
        assertThrows(IllegalArgumentException::class.java) { SentenceWaveClip.readPcm16(ByteArray(0).inputStream(), 0) }
    }
}
