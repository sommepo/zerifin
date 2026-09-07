package org.jellyfin.mobile.player.subtitle

import org.jellyfin.mobile.player.anki.AnkiDuplicateStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleMiningStateTest {
    @Test
    fun `completed and in-progress actions cannot be activated again`() {
        assertEquals(
            listOf(true, false, false, false, true),
            SubtitleMiningState.entries.map { it.actionPresentation().isEnabled },
        )
    }

    @Test
    fun `states use compact recognizable glyphs`() {
        assertEquals("+", SubtitleMiningState.IDLE.actionPresentation().glyph)
        assertEquals("\u2026", SubtitleMiningState.ADDING.actionPresentation().glyph)
        assertTrue(SubtitleMiningState.ADDED.actionPresentation().showBook)
        assertTrue(SubtitleMiningState.DUPLICATE.actionPresentation().showBook)
        assertEquals("!", SubtitleMiningState.ERROR.actionPresentation().glyph)
    }

    @Test
    fun `preflight uses a book for existing words and plus for new words`() {
        val existing = SubtitleMiningState.IDLE.actionPresentation(AnkiDuplicateStatus.EXISTS)
        assertTrue(existing.showBook)
        assertTrue(existing.isEnabled)
        assertEquals("", existing.glyph)
        val fresh = SubtitleMiningState.IDLE.actionPresentation(AnkiDuplicateStatus.NEW)
        assertFalse(fresh.showBook)
        assertEquals("+", fresh.glyph)
        assertEquals("…", SubtitleMiningState.ADDING.actionPresentation(AnkiDuplicateStatus.EXISTS).glyph)
    }
}
