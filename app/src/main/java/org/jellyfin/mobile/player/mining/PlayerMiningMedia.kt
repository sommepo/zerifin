package org.jellyfin.mobile.player.mining

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.mobile.player.anki.AnkiMediaFile
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class MiningMediaContext(
    val serverUrl: String,
    val itemId: String,
    val sourceId: String,
    val subtitleIndex: Int,
    val englishIndex: Int?,
    val japaneseAudioIndex: Int?,
    val positionMs: Long,
    val youTubeSession: String? = null,
)

data class MiningSentence(val cue: MiningCue?, val english: String?)

internal data class EnglishWindow(val media: MiningMediaContext, val cues: List<MiningCue>) {
    fun contains(other: MiningMediaContext): Boolean = media.serverUrl == other.serverUrl &&
        media.itemId == other.itemId && media.sourceId == other.sourceId && media.englishIndex == other.englishIndex &&
        kotlin.math.abs(media.positionMs - other.positionMs) < 45_000
}

/** Authenticated media stays in private cache; URLs and credentials never enter card fields. */
@Suppress("MagicNumber")
class PlayerMiningMedia(context: Context, private val api: ApiClient) {
    private val directory = File(context.applicationContext.cacheDir, "anki-media")
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private val cleanupClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()

    fun capture(source: JellyfinMediaSource?, positionMs: Long): MiningMediaContext? {
        if (source is org.jellyfin.mobile.player.source.YouTubeMediaSource) {
            val playback = source.playback
            return MiningMediaContext(playback.resolverUrl, playback.id, source.id,
                source.selectedSubtitleStreamIndex,
                source.subtitleStreams.firstOrNull { it.language == "en" }?.index,
                if (playback.japaneseAudio) 1 else null, positionMs, playback.session)
        }
        if (source !is RemoteJellyfinMediaSource) return null
        val base = api.baseUrl ?: return null
        val subtitle = source.selectedSubtitleStream
        val english = source.subtitleStreams.filter { SubtitleTimeline.languageMatches(it.language, "en") }
            .filter { it.codec?.lowercase() in setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa") }
            .minByOrNull { if (it.isForced) 1 else 0 }
        val japanese = source.selectedAudioStream?.takeIf { SubtitleTimeline.languageMatches(it.language, "ja") }
            ?: source.audioStreams.firstOrNull { SubtitleTimeline.languageMatches(it.language, "ja") }
        return MiningMediaContext(
            base,
            source.itemId.toString(),
            source.id,
            subtitle?.index ?: -1,
            english?.index,
            japanese?.index,
            positionMs
        )
    }

    private var englishWindow: EnglishWindow? = null

    /** Only requested while the English overlay is open; a bounded in-memory window avoids repeat downloads. */
    suspend fun englishAt(media: MiningMediaContext): String? {
        val index = media.englishIndex ?: return null
        require(media.youTubeSession != null || api.baseUrl == media.serverUrl) { "Playback server changed" }
        val cached = englishWindow?.takeIf { it.contains(media) }
            ?: EnglishWindow(media, subtitles(media, index)).also { englishWindow = it }
        return SubtitleTimeline.atPosition(cached.cues, media.positionMs)
    }

    suspend fun sentence(media: MiningMediaContext, text: String, includeEnglish: Boolean): MiningSentence = withContext(
        Dispatchers.IO
    ) {
        coroutineScope {
            val translation = media.englishIndex?.takeIf { includeEnglish }?.let { index ->
                async { subtitles(media, index) }
            }
            val japanese = if (media.subtitleIndex >= 0) subtitles(media, media.subtitleIndex) else emptyList()
            val cue = SubtitleTimeline.matchingCue(japanese, media.positionMs, text)
            val english = if (cue != null && translation != null) {
                SubtitleTimeline.translation(cue, translation.await())
            } else {
                translation?.cancel()
                null
            }
            MiningSentence(cue, english)
        }
    }

    suspend fun sentenceAudio(media: MiningMediaContext, cue: MiningCue): AnkiMediaFile? {
        val audioIndex = media.japaneseAudioIndex ?: return null
        require(cue.startMs >= 0 && cue.endMs - cue.startMs in 1..SentenceWaveClip.MAX_DURATION_MS)
        var outputFile: File? = null
        var delivered = false
        try {
            val clip = withContext(Dispatchers.IO) {
                val bytes = downloadSentence(media, cue, audioIndex)
                currentCoroutineContext().ensureActive()
                check(directory.isDirectory || directory.mkdirs()) { "Mining audio cache is unavailable" }
                val file = File.createTempFile("sentence-", ".wav", directory)
                outputFile = file
                file.writeBytes(bytes)
                AnkiMediaFile(file, "audio/wav")
            }
            delivered = true
            return clip
        } finally {
            if (!delivered) outputFile?.delete()
        }
    }

    private suspend fun downloadSentence(media: MiningMediaContext, cue: MiningCue, audioIndex: Int): ByteArray {
        if (media.youTubeSession != null) {
            val endpoint = youTubeUrl(media, "sentence.pcm").newBuilder()
                .addQueryParameter("start", (cue.startMs / 1000.0).toString())
                .addQueryParameter("end", (cue.endMs / 1000.0).toString()).build()
            val bytes = org.jellyfin.mobile.youtube.YouTubeClient.get(endpoint.toString())
            return SentenceWaveClip.readPcm16(bytes.inputStream(), cue.endMs - cue.startMs)
        }
        val session = UUID.randomUUID().toString().replace("-", "")
        val device = api.deviceInfo.id
        // Explicit raw PCM avoids WAV headers being stripped by older Jellyfin PCM transcoders.
        val url = url(media, "Audio", media.itemId, "stream.pcm").newBuilder()
            .addQueryParameter("MediaSourceId", media.sourceId)
            .addQueryParameter("AudioStreamIndex", audioIndex.toString())
            .addQueryParameter("StartTimeTicks", (cue.startMs * 10_000).toString())
            .addQueryParameter("AudioCodec", "pcm_s16le")
            // Older servers also use AudioBitRate for a PCM -ar argument. PCM ignores bitrate;
            // supplying the requested sample rate keeps both old and corrected servers compatible.
            .addQueryParameter("AudioBitRate", SentenceWaveClip.PCM_SAMPLE_RATE.toString())
            .addQueryParameter("AudioChannels", SentenceWaveClip.PCM_CHANNELS.toString())
            .addQueryParameter("AudioSampleRate", SentenceWaveClip.PCM_SAMPLE_RATE.toString())
            .addQueryParameter("EnableAutoStreamCopy", "false")
            .addQueryParameter("AllowAudioStreamCopy", "false")
            .addQueryParameter("DeviceId", device)
            .addQueryParameter("PlaySessionId", session).build()
        return try {
            request(url) { stream -> SentenceWaveClip.readPcm16(stream, cue.endMs - cue.startMs) }
        } finally {
            stopClip(media, device, session)
        }
    }

    private suspend fun stopClip(media: MiningMediaContext, device: String, session: String) {
        // This session belongs only to the clip, so stopping it never stops the player.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val stopUrl = url(media, "Videos", "ActiveEncodings").newBuilder()
                    .addQueryParameter("DeviceId", device).addQueryParameter("PlaySessionId", session).build()
                cleanupClient.newCall(authenticated(stopUrl).delete().build()).execute().close()
            } catch (_: IOException) {
                // Closing the progressive stream also lets the server expire this session.
            } catch (_: IllegalArgumentException) {
                // Do not send the previous server's cleanup request after switching accounts.
            }
        }
    }

    private suspend fun subtitles(media: MiningMediaContext, index: Int): List<MiningCue> {
        if (media.youTubeSession != null) {
            val language = if (index == media.englishIndex) "en" else "ja"
            val bytes = org.jellyfin.mobile.youtube.YouTubeClient.get(youTubeUrl(media, "$language.vtt").toString())
            return SubtitleTimeline.parse(bytes.toString(Charsets.UTF_8))
        }
        val url = url(media, "Videos", media.itemId, media.sourceId, "Subtitles", index.toString(), "Stream.vtt")
            .newBuilder().addQueryParameter("CopyTimestamps", "true")
            .addQueryParameter("StartPositionTicks", ((media.positionMs - 60_000).coerceAtLeast(0) * 10_000).toString())
            .addQueryParameter("EndPositionTicks", ((media.positionMs + 60_000) * 10_000).toString()).build()
        return request(url) { stream ->
            val bytes = stream.readBytesBounded(1024 * 1024)
            SubtitleTimeline.parse(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun youTubeUrl(media: MiningMediaContext, name: String): HttpUrl =
        media.serverUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("session")
            .addPathSegment(requireNotNull(media.youTubeSession)).addPathSegment(name).build()

    private fun url(media: MiningMediaContext, vararg segments: String): HttpUrl {
        require(api.baseUrl == media.serverUrl) { "Playback server changed" }
        return media.serverUrl.trimEnd('/').toHttpUrl().newBuilder().apply {
            segments.forEach(::addPathSegment)
        }.build()
    }

    private fun authenticated(url: HttpUrl): Request.Builder = Request.Builder().url(url).header(
        "Authorization",
        AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        ),
    )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(url: HttpUrl, decode: (InputStream) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(authenticated(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Mining media request failed")
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!response.isSuccessful) throw IOException("Mining media is unavailable")
                            decode(requireNotNull(response.body).byteStream())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Mining media request failed")
                            )
                        }
                    } catch (_: RuntimeException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Mining media format is unsupported")
                            )
                        }
                    }
                }
            })
        }

    private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "Subtitle response too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
