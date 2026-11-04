package org.jellyfin.mobile.player.ui

import org.jellyfin.mobile.player.mining.MiningCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleListIndexTest {
    private val cues = listOf(MiningCue(1_000, 2_000, "一"), MiningCue(5_000, 6_000, "二"), MiningCue(9_000, 9_500, "三"))

    @Test
    fun `highlights the playing line or the latest one before it`() {
        assertEquals(-1, SubtitleListIndex.activeLine(cues, 500))
        assertEquals(0, SubtitleListIndex.activeLine(cues, 1_500))
        assertEquals(0, SubtitleListIndex.activeLine(cues, 4_000))
        assertEquals(2, SubtitleListIndex.activeLine(cues, 60_000))
    }

    @Test
    fun `formats line times`() {
        assertEquals("0:05", SubtitleListIndex.formatTime(5_400))
        assertEquals("12:03", SubtitleListIndex.formatTime(723_000))
        assertEquals("1:02:03", SubtitleListIndex.formatTime(3_723_000))
    }
}
