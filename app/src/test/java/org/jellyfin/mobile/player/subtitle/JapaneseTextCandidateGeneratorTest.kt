package org.jellyfin.mobile.player.subtitle

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JapaneseTextCandidateGeneratorTest {
    @Test
    fun `both characters of yesterday produce the complete term`() {
        val subtitle = "昨日は楽しかった"

        candidates(subtitle, 0) shouldContain "昨日"
        candidates(subtitle, 1) shouldContain "昨日"
    }

    @Test
    fun `kana reading spanning tap is produced`() {
        val subtitle = "きのう映画を見た"

        candidates(subtitle, 1) shouldContain "きのう"
    }

    @Test
    fun `candidate retains the exact subtitle source range`() {
        val candidate = JapaneseTextCandidateGenerator.generate("明日は昨日より暑い", 4)
            .first { it.text == "昨日" }

        candidate.sourceStart shouldBe 3
        candidate.sourceLength shouldBe 2
    }

    @Test
    fun `common inflections produce dictionary forms`() {
        candidates("映画を食べました", 4) shouldContain "食べる"
        candidates("本を書いて", 3) shouldContain "書く"
        candidates("値段が高かった", 5) shouldContain "高い"
    }

    @Test
    fun `non Japanese tap produces no dictionary candidates`() {
        JapaneseTextCandidateGenerator.generate("hello", 1) shouldBe emptyList()
    }

    @Test
    fun `candidate never crosses punctuation`() {
        candidates("昨日、会う", 0) shouldNotContain "昨日、会う"
    }

    @Test
    fun `spoken chained forms and negative past restore dictionary verbs`() {
        candidates("書かなかった", 0) shouldContain "書く"
        candidates("食べられなかった", 0) shouldContain "食べる"
        candidates("読んでいた", 0) shouldContain "読む"
        candidates("買っちゃった", 0) shouldContain "買う"
        candidates("話せます", 0) shouldContain "話す"
        candidates("高くなかった", 0) shouldContain "高い"
        candidates("した", 0) shouldContain "する"
    }

    @Test
    fun `normalized half width kana retain original source offsets`() {
        val candidate = JapaneseTextCandidateGenerator.generate("昨日はｺｰﾋｰ", 5).first { it.text == "コーヒー" }
        candidate.sourceStart shouldBe 3
        candidate.sourceLength shouldBe 4
    }

    @Test
    fun `supplementary kanji work from either surrogate without splitting glyphs`() {
        candidates("𠮟る", 0) shouldContain "𠮟る"
        candidates("𠮟る", 1) shouldContain "𠮟る"
        JapaneseTextCandidateGenerator.generate("𠮟る", 1).first { it.text == "𠮟る" }.sourceLength shouldBe 3
    }

    @Test
    fun `a short word remains available in a long unpunctuated subtitle`() {
        candidates("昨日見せられなかった映画を食べながら見ていた", 1) shouldContain "昨日"
    }

    private fun candidates(text: String, offset: Int): List<String> =
        JapaneseTextCandidateGenerator.generate(text, offset).map(JapaneseTextCandidate::text)
}
