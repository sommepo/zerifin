package org.jellyfin.mobile.player.mining

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads only one bounded PCM sentence from a progressive WAV stream. */
@Suppress("MagicNumber")
object SentenceWaveClip {
    const val MAX_DURATION_MS = 30_000L
    const val PCM_SAMPLE_RATE = 24_000
    const val PCM_CHANNELS = 1

    /** The request explicitly asks for signed little-endian 16-bit PCM with these parameters. */
    fun readPcm16(input: InputStream, durationMs: Long): ByteArray {
        require(durationMs in 1..MAX_DURATION_MS)
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)
            putShort(PCM_CHANNELS.toShort())
            putInt(PCM_SAMPLE_RATE)
            putInt(PCM_SAMPLE_RATE * PCM_CHANNELS * 2)
            putShort((PCM_CHANNELS * 2).toShort())
            putShort(16)
        }.array()
        return readSamples(input, format, Long.MAX_VALUE, durationMs)
    }

    private const val MAX_HEADER_BYTES = 65_536

    fun read(input: InputStream, durationMs: Long): ByteArray {
        require(durationMs in 1..MAX_DURATION_MS)
        val stream = DataInputStream(input)
        val header = ByteArray(12).also(stream::readFully)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
        var format: ByteArray? = null
        var scanned = 12L
        while (scanned < MAX_HEADER_BYTES) {
            val chunk = ByteArray(8).also(stream::readFully)
            val name = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (name == "data") return readSamples(stream, requireNotNull(format), size, durationMs)
            require(size <= MAX_HEADER_BYTES && scanned + size + 8 <= MAX_HEADER_BYTES)
            val data = ByteArray(size.toInt()).also(stream::readFully)
            if (name == "fmt ") format = data
            if (size % 2L != 0L) stream.readByte()
            scanned += size + 8 + size % 2
        }
        error("Missing WAV samples")
    }

    private fun readSamples(stream: InputStream, format: ByteArray, dataSize: Long, durationMs: Long): ByteArray {
        require(format.size >= 16)
        val values = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
        val encoding = values.short.toInt() and 0xffff
        val channels = values.short.toInt()
        val sampleRate = values.int
        values.int // byte rate, reconstructed from validated sample format below
        val blockSize = values.short.toInt()
        val bits = values.short.toInt()
        val pcm = encoding == 1 || (
            encoding == 65534 && format.size >= 40 &&
                format.copyOfRange(24, 40).contentEquals(
                    byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113),
                )
            )
        require(pcm && channels in 1..2 && sampleRate in 8000..48000 && bits == 16 && blockSize == channels * 2)
        val wanted = (durationMs * sampleRate / 1000 * blockSize).toInt()
        val limit = minOf(wanted.toLong(), dataSize).toInt()
        val output = ByteArrayOutputStream(limit)
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        val samples = output.toByteArray()
        require(samples.size == wanted) { "Incomplete sentence audio" }
        val length = samples.size - samples.size % blockSize
        return ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII));
            putInt(36 + length)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII));
            putInt(16)
            putShort(1);
            putShort(channels.toShort());
            putInt(sampleRate)
            putInt(sampleRate * blockSize);
            putShort(blockSize.toShort());
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII));
            putInt(length);
            put(samples, 0, length)
        }.array()
    }
}
