package org.jellyfin.mobile.player.mining

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("MagicNumber")
class SentenceWaveClipTest {
    @Test
    fun `reads exactly the cue duration from an unbounded progressive stream`() {
        val samples = ByteArray(3200) { it.toByte() }
        val input = ByteArrayInputStream(wave(samples, dataSize = -1))
        val clip = SentenceWaveClip.read(input, 100)

        assertEquals(1644, clip.size)
        assertEquals(1636, number(clip, 4))
        assertEquals(1600, number(clip, 40))
        assertEquals(8000, number(clip, 24))
        assertEquals(16000, number(clip, 28))
        assertEquals(1600, input.available())
        assertArrayEquals(samples.copyOf(1600), clip.copyOfRange(44, clip.size))
    }

    @Test
    fun `skips padded metadata chunks and accepts extensible PCM`() {
        val format = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(-2);
            putShort(1);
            putInt(8000);
            putInt(16000);
            putShort(2);
            putShort(16)
            putShort(22);
            putShort(16);
            putInt(4)
            put(byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))
        }.array()
        val header = "RIFF".toByteArray() + little(-1) + "WAVE".toByteArray()
        val samples = ByteArray(1600)
        val input = header + chunk("JUNK", byteArrayOf(1, 2, 3)) + chunk("fmt ", format) + chunk("data", samples)

        assertEquals(1644, SentenceWaveClip.read(ByteArrayInputStream(input), 100).size)
    }

    @Test
    fun `rejects truncated samples including a missing partial frame`() {
        for (size in listOf(0, 1598, 1599)) {
            assertThrows(IllegalArgumentException::class.java) {
                SentenceWaveClip.read(ByteArrayInputStream(wave(ByteArray(size), dataSize = -1)), 100)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SentenceWaveClip.read(ByteArrayInputStream(wave(ByteArray(1600), dataSize = 100)), 100)
        }
    }

    @Test
    fun `rejects unsupported formats oversized headers and invalid durations`() {
        val valid = wave(ByteArray(1600))
        for (offset in listOf(20, 22, 24, 32, 34)) {
            val invalid = valid.copyOf().apply { this[offset] = 0 }
            assertThrows(IllegalArgumentException::class.java) {
                SentenceWaveClip.read(ByteArrayInputStream(invalid), 100)
            }
        }
        val oversized = "RIFF".toByteArray() + little(-1) + "WAVEJUNK".toByteArray() + little(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            SentenceWaveClip.read(ByteArrayInputStream(oversized), 100)
        }
        assertThrows(EOFException::class.java) { SentenceWaveClip.read(ByteArrayInputStream(ByteArray(3)), 100) }
        for (duration in listOf(0L, -1L, SentenceWaveClip.MAX_DURATION_MS + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                SentenceWaveClip.read(ByteArrayInputStream(valid), duration)
            }
        }
    }

    private fun wave(samples: ByteArray, dataSize: Int = samples.size): ByteArray {
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1);
            putShort(1);
            putInt(8000);
            putInt(16000);
            putShort(2);
            putShort(16)
        }.array()
        return "RIFF".toByteArray() + little(-1) + "WAVE".toByteArray() + chunk("fmt ", format) +
            "data".toByteArray() + little(dataSize) + samples
    }

    private fun chunk(name: String, data: ByteArray): ByteArray =
        name.toByteArray() + little(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun little(value: Int): ByteArray = ByteBuffer.allocate(
        4
    ).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun number(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(
        bytes,
        offset,
        4
    ).order(ByteOrder.LITTLE_ENDIAN).int
}
