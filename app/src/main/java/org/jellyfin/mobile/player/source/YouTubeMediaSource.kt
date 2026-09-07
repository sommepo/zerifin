package org.jellyfin.mobile.player.source

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import org.jellyfin.mobile.youtube.YouTubePlayback
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

/** An external source feeding the same Media3 player, track selector and interactive subtitle view. */
class YouTubeMediaSource(val playback: YouTubePlayback) : JellyfinMediaSource(
    UUID.nameUUIDFromBytes("youtube:${playback.id}".toByteArray()), null, playback.sourceInfo(), playback.session, null
) {
    override val playMethod = PlayMethod.DIRECT_PLAY
    var useRelay = false

    fun prepare(): MediaSource {
        // Deliberately independent of Jellyfin's authenticated data source and download cache.
        val videoUrl = if (useRelay) playback.resource("video") else playback.videoUrl
        val audioUrl = if (useRelay) playback.resource("audio") else playback.audioUrl
        val data = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
            val headers = when {
                useRelay -> emptyMap()
                spec.uri.toString() == playback.videoUrl -> playback.videoHeaders
                spec.uri.toString() == playback.audioUrl -> playback.audioHeaders
                else -> emptyMap()
            }.filterKeys { it.lowercase() in setOf("user-agent", "referer", "origin", "accept-language") }
            spec.withRequestHeaders(headers)
        }
        val factory = DefaultMediaSourceFactory(data)
        val captions = externalSubtitleStreams.map {
            MediaItem.SubtitleConfiguration.Builder(it.deliveryUrl.toUri())
                .setId("${ExternalSubtitleStream.ID_PREFIX}${it.index}").setMimeType(it.mimeType)
                .setLanguage(it.language).setLabel(it.displayTitle)
                .setSelectionFlags(if (it.index == selectedSubtitleStreamIndex) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val video = factory.createMediaSource(MediaItem.Builder().setMediaId(itemId.toString())
            .setUri(videoUrl).setMimeType(playback.videoMime).setSubtitleConfigurations(captions).build())
        return if (playback.audioUrl != null) {
            val audio = factory.createMediaSource(MediaItem.Builder().setUri(audioUrl).setMimeType(playback.audioMime).build())
            MergingMediaSource(video, audio)
        } else video
    }
}

private fun YouTubePlayback.sourceInfo(): MediaSourceInfo {
    val streams = mutableListOf(
        MediaStream(type = MediaStreamType.VIDEO, index = 0, width = width, height = height,
            isInterlaced = false, isDefault = true, isForced = false, isHearingImpaired = false,
            isExternal = false, isTextSubtitleStream = false, supportsExternalStream = false),
        MediaStream(type = MediaStreamType.AUDIO, index = 1, language = if (japaneseAudio) "ja" else null,
            isInterlaced = false, isDefault = true, isForced = false, isHearingImpaired = false,
            isExternal = false, isTextSubtitleStream = false, supportsExternalStream = false),
    )
    if (japaneseCaptions) streams.add(caption(2, "ja", "Japanese"))
    if (englishCaptions) streams.add(caption(streams.size, "en", "English"))
    return MediaSourceInfo(id = session, name = title, runTimeTicks = durationMs * 10_000,
        defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = if (japaneseCaptions) 2 else -1, mediaStreams = streams,
        protocol = org.jellyfin.sdk.model.api.MediaProtocol.HTTP,
        type = org.jellyfin.sdk.model.api.MediaSourceType.DEFAULT,
        isRemote = true, readAtNativeFramerate = false, ignoreDts = false, ignoreIndex = false,
        genPtsInput = false, supportsTranscoding = false, supportsDirectStream = false, supportsDirectPlay = true,
        isInfiniteStream = false, useMostCompatibleTranscodingProfile = false, requiresOpening = false,
        requiresClosing = false, requiresLooping = false, supportsProbing = false, hasSegments = false,
        transcodingSubProtocol = org.jellyfin.sdk.model.api.MediaStreamProtocol.HLS)
}

private fun YouTubePlayback.caption(index: Int, language: String, label: String) = MediaStream(
    type = MediaStreamType.SUBTITLE, index = index, codec = "vtt", language = language,
    isInterlaced = false, isDefault = language == "ja", isForced = false, isHearingImpaired = false,
    isTextSubtitleStream = true, supportsExternalStream = true,
    displayTitle = label, isExternal = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
    deliveryUrl = resource("$language.vtt"),
)
