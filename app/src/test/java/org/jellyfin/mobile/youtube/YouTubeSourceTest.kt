package org.jellyfin.mobile.youtube

import kotlinx.serialization.json.Json
import org.jellyfin.mobile.player.source.YouTubeMediaSource
import org.jellyfin.mobile.player.mining.MiningMediaContext
import org.jellyfin.mobile.player.mining.SubtitleTimeline
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YouTubeSourceTest {
    private fun playback(japanese: Boolean = true, english: Boolean = true) = YouTubePlayback(
        id = "nS4m2eXb4nU", title = "Japanese listening", session = "test-session",
        videoUrl = "https://v.googlevideo.com/video", audioUrl = "https://v.googlevideo.com/audio",
        videoMime = "video/mp4", audioMime = "audio/webm", durationMs = 100000,
        japaneseCaptions = japanese, englishCaptions = english, japaneseAudio = true,
        resolverUrl = "http://127.0.0.1:8767",
    )

    @Test
    fun `external captions enter existing stream model with contiguous indices`() {
        val source = YouTubeMediaSource(playback())
        assertEquals(listOf(0, 1, 2, 3), source.mediaStreams.map { it.index })
        assertEquals("ja", source.selectedSubtitleStream?.language)
        assertEquals("vtt", source.selectedSubtitleStream?.codec)
        assertEquals("ja", source.selectedAudioStream?.language)
        assertTrue(source.selectSubtitleStream(source.subtitleStreams.last()))
        assertEquals("en", source.selectedSubtitleStream?.language)
        assertEquals("http://127.0.0.1:8767/session/test-session/ja.vtt", source.externalSubtitleStreams.first().deliveryUrl)
    }

    @Test
    fun `missing Japanese captions does not silently select English`() {
        val source = YouTubeMediaSource(playback(japanese = false))
        assertNull(source.selectedSubtitleStream)
        assertEquals(listOf(0, 1, 2), source.mediaStreams.map { it.index })
        assertEquals("en", source.subtitleStreams.single().language)
    }

    @Test
    fun `playback and pending mining survive serialization`() {
        val original = playback()
        assertEquals(original, Json.decodeFromString<YouTubePlayback>(Json.encodeToString(original)))
        val mining = MiningMediaContext(original.resolverUrl, original.id, original.session, 2, 3, 1, 12000, original.session)
        assertEquals(mining, Json.decodeFromString<MiningMediaContext>(Json.encodeToString(mining)))
        val legacy = """{"serverUrl":"http://jellyfin","itemId":"item","sourceId":"source","subtitleIndex":2,"englishIndex":null,"japaneseAudioIndex":1,"positionMs":12000}"""
        assertNull(Json.decodeFromString<MiningMediaContext>(legacy).youTubeSession)
    }

    @Test
    fun `normalized captions use existing seek and mining timeline`() {
        val japanese = SubtitleTimeline.parse("WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n今日は晴れです。\n\n00:00:04.000 --> 00:00:06.000\n次の文です。")
        val english = SubtitleTimeline.parse("WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nIt is sunny today.")
        assertEquals("次の文です。", SubtitleTimeline.atPosition(japanese, 4500))
        assertEquals("今日は晴れです。", SubtitleTimeline.atPosition(japanese, 1200))
        val cue = SubtitleTimeline.matchingCue(japanese, 1200, "今日は晴れです。")!!
        assertEquals("It is sunny today.", SubtitleTimeline.translation(cue, english))
        assertNull(SubtitleTimeline.atPosition(japanese, 6000))
    }
}
