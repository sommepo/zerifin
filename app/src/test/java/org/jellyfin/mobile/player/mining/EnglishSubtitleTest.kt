package org.jellyfin.mobile.player.mining

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnglishSubtitleTest {
    private val media = MiningMediaContext("https://example.invalid", "item", "source", 1, 2, 3, 100_000)

    @Test
    fun `subtitle cache expires before its downloaded window ends`() {
        val window = SubtitleWindow(media, 2, emptyList())
        assertTrue(window.contains(media.copy(positionMs = 144_999), 2))
        assertTrue(window.contains(media.copy(positionMs = 55_001), 2))
        assertFalse(window.contains(media.copy(positionMs = 145_000), 2))
        assertFalse(window.contains(media.copy(positionMs = 55_000), 2))
    }

    @Test
    fun `switching server item source or English track invalidates the window`() {
        val window = SubtitleWindow(media, 2, emptyList())
        assertFalse(window.contains(media.copy(serverUrl = "https://other.invalid"), 2))
        assertFalse(window.contains(media.copy(itemId = "other"), 2))
        assertFalse(window.contains(media.copy(sourceId = "other"), 2))
        assertFalse(window.contains(media.copy(englishIndex = 4), 4))
    }

    @Test
    fun `current subtitle includes overlaps and clears at the end of a cue`() {
        val cues = listOf(MiningCue(1000, 3000, "First"), MiningCue(2000, 4000, "Second"))
        assertNull(SubtitleTimeline.atPosition(cues, 999))
        assertEquals("First", SubtitleTimeline.atPosition(cues, 1000))
        assertEquals("First\nSecond", SubtitleTimeline.atPosition(cues, 2000))
        assertEquals("Second", SubtitleTimeline.atPosition(cues, 3000))
        assertNull(SubtitleTimeline.atPosition(cues, 4000))
    }
}
