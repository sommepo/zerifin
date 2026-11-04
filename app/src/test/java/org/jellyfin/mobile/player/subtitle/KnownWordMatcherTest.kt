package org.jellyfin.mobile.player.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KnownWordMatcherTest {
    @Test
    fun `normalises Anki fields with markup and furigana`() {
        assertEquals("食べる", KnownWordMatcher.normalize("<b>食べる</b>"))
        assertEquals("漢字", KnownWordMatcher.normalize(" 漢字[かんじ] "))
        assertEquals("カタカナ", KnownWordMatcher.normalize("ｶﾀｶﾅ"))
        assertNull(KnownWordMatcher.normalize("english only"))
        assertNull(KnownWordMatcher.normalize("   "))
    }

    @Test
    fun `finds known words including inflected forms and prefers the longest`() {
        val text = "昨日ケーキを食べました"
        val ranges = KnownWordMatcher.find(text, setOf("昨日", "ケーキ", "食べる", "食"))

        assertEquals(
            listOf("昨日", "ケーキ", "食べました"),
            ranges.map { text.substring(it.start, it.start + it.length) },
        )
    }

    @Test
    fun `ignores lone kana such as particles`() {
        assertEquals(emptyList<KnownWordRange>(), KnownWordMatcher.find("を", setOf("を")))
        assertEquals(emptyList<KnownWordRange>(), KnownWordMatcher.find("知らない", emptySet()))
    }
}
