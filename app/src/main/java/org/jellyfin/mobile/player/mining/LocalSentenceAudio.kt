package org.jellyfin.mobile.player.mining

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.jellyfin.mobile.utils.AndroidVersion
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts one sentence from a downloaded video's audio track on the device: the track is decoded
 * with the platform codecs and down-mixed to mono 16-bit PCM in a WAV file.
 */
@Suppress("MagicNumber")
object LocalSentenceAudio {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 2_000

    /**
     * @param audioOrdinal the position of the wanted track among the file's audio tracks.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun extract(context: Context, uri: Uri, audioOrdinal: Int, cue: MiningCue): ByteArray {
        require(cue.endMs - cue.startMs in 1..SentenceWaveClip.MAX_DURATION_MS)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val audioTracks = (0 until extractor.trackCount).filter { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val track = audioTracks.getOrNull(audioOrdinal) ?: throw IOException("Audio track not found")
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val startUs = cue.startMs * 1000
            val endUs = cue.endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val mono = MonoPcmBuffer()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (idle < MAX_IDLE_LOOPS) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = AndroidVersion.isAtLeastN && output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outputIndex >= 0 -> {
                        idle = 0
                        val output = requireNotNull(decoder.getOutputBuffer(outputIndex))
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        mono.append(output.slice(), info.presentationTimeUs, startUs, endUs, sampleRate, channels, floatPcm)
                        decoder.releaseOutputBuffer(outputIndex, false)
                        val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (finished || info.presentationTimeUs >= endUs) break
                    }
                    else -> idle++
                }
            }
            val samples = mono.toByteArray()
            if (samples.isEmpty()) throw IOException("No audio was decoded for this sentence")
            require(sampleRate in 8_000..96_000) { "Unsupported sample rate" }
            return SentenceWaveClip.wav(samples, sampleRate, channels = 1)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    /** Collects mono 16-bit samples that fall inside the sentence. */
    private class MonoPcmBuffer {
        private val output = java.io.ByteArrayOutputStream()

        @Suppress("LongParameterList")
        fun append(
            buffer: ByteBuffer,
            presentationUs: Long,
            startUs: Long,
            endUs: Long,
            sampleRate: Int,
            channels: Int,
            floatPcm: Boolean,
        ) {
            val data = buffer.order(ByteOrder.nativeOrder())
            val bytesPerSample = if (floatPcm) 4 else 2
            val frameCount = data.remaining() / (bytesPerSample * channels)
            for (frame in 0 until frameCount) {
                val timeUs = presentationUs + frame * 1_000_000L / sampleRate
                if (timeUs < startUs) continue
                if (timeUs >= endUs) return
                var sum = 0f
                for (channel in 0 until channels) {
                    val position = (frame * channels + channel) * bytesPerSample
                    sum += if (floatPcm) data.getFloat(position) else data.getShort(position) / 32768f
                }
                val value = (sum / channels * 32767f).toInt().coerceIn(-32768, 32767)
                output.write(value and 0xff)
                output.write(value shr 8 and 0xff)
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }
}
