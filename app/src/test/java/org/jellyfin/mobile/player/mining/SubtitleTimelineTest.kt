package org.jellyfin.mobile.player.mining

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleTimelineTest {
    @Test
    fun `parses absolute WebVTT timestamps and joins matching English cues`() {
        val cues = SubtitleTimeline.parse(
            "WEBVTT\n\n1\n01:02:03.000 --> 01:02:06.000 align:center\n<b>日本語</b>\n字幕\n\n" +
                "2\n01:02:08,000 --> 01:02:10,000\n次の文\n",
        )
        assertEquals(2, cues.size)
        val cue = SubtitleTimeline.matchingCue(cues, 3_724_000, "日本語\n字幕")!!
        assertEquals(3_723_000, cue.startMs)
        assertEquals(
            "Japanese\nsubtitles",
            SubtitleTimeline.translation(
                cue,
                listOf(
                    MiningCue(3_722_800, 3_724_000, "Japanese"),
                    MiningCue(3_724_100, 3_726_100, "subtitles"),
                    MiningCue(3_725_900, 3_729_000, "Unrelated next line"),
                )
            ),
        )
    }

    @Test
    fun `does not guess timing for a stale or different sentence`() {
        val cues = listOf(MiningCue(1000, 4000, "同じ文"), MiningCue(9000, 11000, "同じ文"))
        assertNull(SubtitleTimeline.matchingCue(cues, 5000, "同じ文"))
        assertNull(SubtitleTimeline.matchingCue(cues, 2000, "違う文"))
        assertEquals(9000, SubtitleTimeline.matchingCue(cues, 10000, "同じ文")?.startMs)
        assertNull(SubtitleTimeline.translation(cues.first(), listOf(MiningCue(4000, 5000, "Next"))))
    }

    @Test
    fun `accepts short VTT timestamps and rejects malformed intervals`() {
        assertEquals(
            MiningCue(1500, 2300, "A & B"),
            SubtitleTimeline.parse("00:01.500 --> 00:02.300\nA &amp; B").single()
        )
        assertTrue(SubtitleTimeline.parse("00:10.000 --> 00:09.000\nBad\n\n00:70.000 --> 01:20.000\nBad").isEmpty())
    }

    @Test
    fun `Japanese audio selection must have a known Japanese language`() {
        listOf(
            "ja",
            "jpn",
            "ja-JP",
            "JPN",
            "japanese"
        ).forEach { assertTrue(SubtitleTimeline.languageMatches(it, "ja")) }
        listOf(null, "", "und", "eng", "en-US").forEach { assertFalse(SubtitleTimeline.languageMatches(it, "ja")) }
        assertTrue(SubtitleTimeline.languageMatches("en-GB", "en"))
    }
}
