package org.jellyfin.mobile.player.subtitle

import java.text.Normalizer

/** A dictionary query and its distance from the literal tapped text. */
data class JapaneseTextCandidate(
    val text: String,
    val sourceStart: Int,
    val sourceLength: Int,
    val deinflectionDepth: Int,
)

/**
 * Produces dictionary-sized strings which contain the tapped subtitle character.
 *
 * Dictionary lookup decides word boundaries. Bounded suffix expansion covers common spoken
 * inflections, while normalized query text retains the original subtitle offsets.
 */
object JapaneseTextCandidateGenerator {
    private const val MAX_TERM_LENGTH = 16
    private const val MAX_DEINFLECTION_DEPTH = 4
    private const val MAX_FORMS_PER_LITERAL = 96

    private val suffixRules = listOf(
        "きませんでした" to listOf("く"),
        "ぎませんでした" to listOf("ぐ"),
        "しませんでした" to listOf("す", "する"),
        "ちませんでした" to listOf("つ"),
        "にませんでした" to listOf("ぬ"),
        "びませんでした" to listOf("ぶ"),
        "みませんでした" to listOf("む"),
        "りませんでした" to listOf("る"),
        "いませんでした" to listOf("う"),
        "ませんでした" to listOf("る", "う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む"),
        "きません" to listOf("く"),
        "ぎません" to listOf("ぐ"),
        "しません" to listOf("す", "する"),
        "ちません" to listOf("つ"),
        "にません" to listOf("ぬ"),
        "びません" to listOf("ぶ"),
        "みません" to listOf("む"),
        "りません" to listOf("る"),
        "いません" to listOf("う"),
        "ません" to listOf("る", "う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む"),
        "きました" to listOf("く"),
        "ぎました" to listOf("ぐ"),
        "しました" to listOf("す", "する"),
        "ちました" to listOf("つ"),
        "にました" to listOf("ぬ"),
        "びました" to listOf("ぶ"),
        "みました" to listOf("む"),
        "りました" to listOf("る"),
        "いました" to listOf("う"),
        "ました" to listOf("る"),
        "きます" to listOf("く"),
        "ぎます" to listOf("ぐ"),
        "します" to listOf("す", "する"),
        "ちます" to listOf("つ"),
        "にます" to listOf("ぬ"),
        "びます" to listOf("ぶ"),
        "みます" to listOf("む"),
        "ります" to listOf("る"),
        "います" to listOf("う"),
        "ます" to listOf("る"),
        "かない" to listOf("く"),
        "がない" to listOf("ぐ"),
        "さない" to listOf("す"),
        "たない" to listOf("つ"),
        "なない" to listOf("ぬ"),
        "ばない" to listOf("ぶ"),
        "まない" to listOf("む"),
        "らない" to listOf("る"),
        "わない" to listOf("う"),
        "なかった" to listOf("ない"),
        "なくて" to listOf("ない"),
        "なければ" to listOf("ない"),
        "ない" to listOf("る", "う"),
        "かった" to listOf("い"),
        "くない" to listOf("い"),
        "くて" to listOf("い"),
        "ければ" to listOf("い"),
        "かれる" to listOf("く"),
        "がれる" to listOf("ぐ"),
        "される" to listOf("す", "する"),
        "たれる" to listOf("つ"),
        "なれる" to listOf("ぬ"),
        "ばれる" to listOf("ぶ"),
        "まれる" to listOf("む"),
        "られる" to listOf("る"),
        "われる" to listOf("う"),
        "かせる" to listOf("く"),
        "がせる" to listOf("ぐ"),
        "させる" to listOf("す", "する", "る"),
        "たせる" to listOf("つ"),
        "なせる" to listOf("ぬ"),
        "ばせる" to listOf("ぶ"),
        "ませる" to listOf("む"),
        "らせる" to listOf("る"),
        "わせる" to listOf("う"),
        "ける" to listOf("く"),
        "げる" to listOf("ぐ"),
        "せる" to listOf("す"),
        "てる" to listOf("つ", "ている"),
        "ねる" to listOf("ぬ"),
        "べる" to listOf("ぶ"),
        "める" to listOf("む"),
        "れる" to listOf("る"),
        "える" to listOf("う"),
        "ている" to listOf("て"),
        "でいる" to listOf("で"),
        "でる" to listOf("でいる"),
        "てしまう" to listOf("て"),
        "でしまう" to listOf("で"),
        "ちゃう" to listOf("て"),
        "じゃう" to listOf("で"),
        "たい" to listOf("ます"),
        "こう" to listOf("く"),
        "ごう" to listOf("ぐ"),
        "そう" to listOf("す"),
        "とう" to listOf("つ"),
        "のう" to listOf("ぬ"),
        "ぼう" to listOf("ぶ"),
        "もう" to listOf("む"),
        "ろう" to listOf("る"),
        "おう" to listOf("う"),
        "よう" to listOf("る"),
        "けば" to listOf("く"),
        "げば" to listOf("ぐ"),
        "せば" to listOf("す"),
        "てば" to listOf("つ"),
        "ねば" to listOf("ぬ"),
        "べば" to listOf("ぶ"),
        "めば" to listOf("む"),
        "れば" to listOf("る"),
        "えば" to listOf("う"),
        "った" to listOf("う", "つ", "る"),
        "って" to listOf("う", "つ", "る"),
        "んだ" to listOf("ぬ", "ぶ", "む"),
        "んで" to listOf("ぬ", "ぶ", "む"),
        "いた" to listOf("く"),
        "いて" to listOf("く"),
        "いだ" to listOf("ぐ"),
        "いで" to listOf("ぐ"),
        "した" to listOf("す", "する"),
        "して" to listOf("す", "する"),
        "た" to listOf("る"),
        "て" to listOf("る"),
    )

    private val irregularForms = mapOf(
        "した" to "する",
        "して" to "する",
        "しない" to "する",
        "しよう" to "する",
        "きた" to "くる",
        "きて" to "くる",
        "きます" to "くる",
        "こない" to "くる",
    )

    fun generate(subtitleText: String, tappedCharacterOffset: Int): List<JapaneseTextCandidate> {
        if (tappedCharacterOffset !in subtitleText.indices) return emptyList()
        val tapStart = if (Character.isLowSurrogate(subtitleText[tappedCharacterOffset]) && tappedCharacterOffset > 0 &&
            Character.isHighSurrogate(subtitleText[tappedCharacterOffset - 1])
        ) {
            tappedCharacterOffset - 1
        } else {
            tappedCharacterOffset
        }
        if (!isJapanese(subtitleText.codePointAt(tapStart))) return emptyList()

        var spanStart = tapStart
        while (spanStart > 0 && isJapanese(subtitleText.codePointBefore(spanStart))) {
            spanStart -= Character.charCount(subtitleText.codePointBefore(spanStart))
        }
        var spanEnd = tapStart + Character.charCount(subtitleText.codePointAt(tapStart))
        while (spanEnd < subtitleText.length && isJapanese(subtitleText.codePointAt(spanEnd))) {
            spanEnd += Character.charCount(subtitleText.codePointAt(spanEnd))
        }
        val boundaries = buildList {
            var offset = spanStart
            add(offset)
            while (offset < spanEnd) {
                offset += Character.charCount(subtitleText.codePointAt(offset))
                add(offset)
            }
        }
        val tappedBoundary = boundaries.indexOf(tapStart)

        val literalCandidates = buildList {
            for (start in maxOf(0, tappedBoundary - MAX_TERM_LENGTH + 1)..tappedBoundary) {
                val maximumEnd = minOf(boundaries.lastIndex, start + MAX_TERM_LENGTH)
                for (end in (tappedBoundary + 1)..maximumEnd) {
                    add(LiteralCandidate(subtitleText.substring(boundaries[start], boundaries[end]), boundaries[start]))
                }
            }
        }.distinctBy(LiteralCandidate::text).sortedByDescending { it.text.length }

        val results = linkedMapOf<String, JapaneseTextCandidate>()
        literalCandidates.forEach { literal ->
            val normalized = Normalizer.normalize(literal.text, Normalizer.Form.NFKC)
            results.putIfAbsent(
                normalized,
                JapaneseTextCandidate(normalized, literal.sourceStart, literal.text.length, 0),
            )
            deinflect(normalized).forEach { (term, depth) ->
                results.putIfAbsent(
                    term,
                    JapaneseTextCandidate(term, literal.sourceStart, literal.text.length, depth),
                )
            }
        }
        return results.values.toList()
    }

    private data class LiteralCandidate(
        val text: String,
        val sourceStart: Int,
    )

    @Suppress("NestedBlockDepth")
    private fun deinflect(source: String): Map<String, Int> {
        val discovered = linkedMapOf(source to 0)
        val queue = ArrayDeque<String>().apply { add(source) }
        while (queue.isNotEmpty() && discovered.size < MAX_FORMS_PER_LITERAL) {
            val current = queue.removeFirst()
            val depth = discovered.getValue(current)
            if (depth >= MAX_DEINFLECTION_DEPTH) continue
            irregularForms[current]?.let { candidate ->
                if (discovered.putIfAbsent(candidate, depth + 1) == null) queue.add(candidate)
            }
            suffixRules.forEach { (suffix, replacements) ->
                if (!current.endsWith(suffix) || current.length <= suffix.length) return@forEach
                replacements.forEach { replacement ->
                    val candidate = current.dropLast(suffix.length) + replacement
                    if (discovered.putIfAbsent(candidate, depth + 1) == null) queue.add(candidate)
                }
            }
        }
        discovered.remove(source)
        return discovered
    }

    private fun isJapanese(character: Int): Boolean = character in 0x3040..0x30ff ||
        character in 0x3400..0x4dbf || character in 0x4e00..0x9fff || character in 0x20000..0x323af ||
        character in 0xff66..0xff9f || character in 0xff10..0xff19 || character in 0x30..0x39 ||
        character == 0x3005
}
