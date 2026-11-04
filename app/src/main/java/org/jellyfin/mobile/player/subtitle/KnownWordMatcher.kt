package org.jellyfin.mobile.player.subtitle

import java.text.Normalizer

/** A run of subtitle characters that spells a word the viewer already knows. */
data class KnownWordRange(val start: Int, val length: Int)

/**
 * Finds known words in a subtitle line without a dictionary: at each position, the longest
 * literal (up to [MAX_WORD_LENGTH] characters) whose surface or deinflected form is known wins.
 */
object KnownWordMatcher {
    private const val MAX_WORD_LENGTH = 12

    /** Normalise an Anki field or word-list line to the form lookups compare. */
    fun normalize(raw: String): String? {
        val text = raw
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("\\[[^\\]]*]"), "")
            .substringBefore('\t')
            .trim()
        if (text.isEmpty() || text.length > MAX_WORD_LENGTH * 2) return null
        val normalized = Normalizer.normalize(text.replace(" ", ""), Normalizer.Form.NFKC)
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (JapaneseTextCandidateGenerator.isJapaneseCodePoint(codePoint)) return normalized
            offset += Character.charCount(codePoint)
        }
        return null
    }

    fun find(text: String, known: Set<String>): List<KnownWordRange> {
        if (known.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = ArrayList<KnownWordRange>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val step = Character.charCount(codePoint)
            if (!JapaneseTextCandidateGenerator.isJapaneseCodePoint(codePoint)) {
                offset += step
                continue
            }
            val length = longestKnownAt(text, offset, known)
            if (length > 0) {
                ranges += KnownWordRange(offset, length)
                offset += length
            } else {
                offset += step
            }
        }
        return ranges
    }

    private fun longestKnownAt(text: String, start: Int, known: Set<String>): Int {
        val ends = ArrayList<Int>()
        var end = start
        while (end < text.length && ends.size < MAX_WORD_LENGTH) {
            val codePoint = text.codePointAt(end)
            if (!JapaneseTextCandidateGenerator.isJapaneseCodePoint(codePoint)) break
            end += Character.charCount(codePoint)
            ends += end
        }
        for (candidateEnd in ends.asReversed()) {
            val literal = text.substring(start, candidateEnd)
            // A lone kana is nearly always a particle; tinting it would be noise.
            if (literal.length == 1 && isKana(literal[0])) continue
            if (JapaneseTextCandidateGenerator.baseForms(literal).any(known::contains)) {
                return candidateEnd - start
            }
        }
        return 0
    }

    private fun isKana(character: Char): Boolean = character in '぀'..'ヿ'
}
