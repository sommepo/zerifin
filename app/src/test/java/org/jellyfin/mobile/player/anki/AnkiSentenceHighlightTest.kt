package org.jellyfin.mobile.player.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnkiSentenceHighlightTest {
    private val preset = AnkiMiningPreset(deckId = 1, deckName = "Deck", modelId = 2, modelName = "Model", fields = listOf(
        AnkiFieldMapping("Word", AnkiFieldSource.WORD), AnkiFieldMapping("Sentence", AnkiFieldSource.SUBTITLE)))

    private fun fields(snapshot: AnkiMiningSnapshot): List<String> =
        (AnkiNoteBuilder.build(preset, listOf("Word", "Sentence"), snapshot) as AnkiNoteBuildResult.Ready).note.fields

    @Test
    fun `bolds a conjugated surface form and leaves the duplicate field untouched`() {
        val snapshot = AnkiMiningSnapshot("食べる", "たべる", "eat", "昨日食べました。",
            subtitleHighlightStart = 2, subtitleHighlightLength = 5)
        assertEquals(listOf("食べる", "昨日<b>食べました</b>。"), fields(snapshot))
    }

    @Test
    fun `bolds only the tapped occurrence of a repeated word`() {
        assertEquals("猫と<b>猫</b>", fields(AnkiMiningSnapshot("猫", definition = "cat", subtitle = "猫と猫",
            subtitleHighlightStart = 2, subtitleHighlightLength = 1))[1])
    }

    @Test
    fun `escapes sentence text around and inside the bold markup`() {
        assertEquals("&lt;x&gt;<b>&amp;</b><br>&lt;/x&gt;", fields(AnkiMiningSnapshot("&", definition = "and", subtitle = "<x>&\n</x>"))[1])
    }

    @Test
    fun `invalid offsets do not split surrogate pairs or lose subtitle content`() {
        assertEquals("😀晴れ", fields(AnkiMiningSnapshot("昨日", definition = "yesterday", subtitle = "😀晴れ",
            subtitleHighlightStart = 1, subtitleHighlightLength = 1))[1])
        assertEquals("<b>猫</b>", fields(AnkiMiningSnapshot("猫", definition = "cat", subtitle = "猫",
            subtitleHighlightStart = Int.MAX_VALUE, subtitleHighlightLength = 1))[1])
    }
}
