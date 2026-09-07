package org.jellyfin.mobile.player.subtitle

import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.ADVERBIAL
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.CAUSATIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.COLLOQUIAL
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.COMPLETIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.CONDITIONAL
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.DESIDERATIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.NEGATIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.PASSIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.PASSIVE_OR_POTENTIAL
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.PAST
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.POLITE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.POTENTIAL
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.PROGRESSIVE
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.TE_FORM
import org.jellyfin.mobile.player.subtitle.JapaneseConjugation.VOLITIONAL
import java.text.Normalizer

/** A dictionary query and its distance from the literal tapped text. */
data class JapaneseTextCandidate(
    val text: String,
    val sourceStart: Int,
    val sourceLength: Int,
    val deinflectionDepth: Int,
    val conjugations: List<JapaneseConjugation> = emptyList(),
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

    private data class SuffixRule(
        val suffix: String,
        val replacements: List<String>,
        val conjugations: List<JapaneseConjugation>,
    )

    private fun rule(suffix: String, replacements: List<String>, vararg conjugations: JapaneseConjugation) =
        SuffixRule(suffix, replacements, conjugations.toList())

    private val suffixRules = listOf(
        rule("きませんでした", listOf("く"), POLITE, NEGATIVE, PAST),
        rule("ぎませんでした", listOf("ぐ"), POLITE, NEGATIVE, PAST),
        rule("しませんでした", listOf("す", "する"), POLITE, NEGATIVE, PAST),
        rule("ちませんでした", listOf("つ"), POLITE, NEGATIVE, PAST),
        rule("にませんでした", listOf("ぬ"), POLITE, NEGATIVE, PAST),
        rule("びませんでした", listOf("ぶ"), POLITE, NEGATIVE, PAST),
        rule("みませんでした", listOf("む"), POLITE, NEGATIVE, PAST),
        rule("りませんでした", listOf("る"), POLITE, NEGATIVE, PAST),
        rule("いませんでした", listOf("う"), POLITE, NEGATIVE, PAST),
        rule("ませんでした", listOf("る", "う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む"), POLITE, NEGATIVE, PAST),
        rule("きません", listOf("く"), POLITE, NEGATIVE),
        rule("ぎません", listOf("ぐ"), POLITE, NEGATIVE),
        rule("しません", listOf("す", "する"), POLITE, NEGATIVE),
        rule("ちません", listOf("つ"), POLITE, NEGATIVE),
        rule("にません", listOf("ぬ"), POLITE, NEGATIVE),
        rule("びません", listOf("ぶ"), POLITE, NEGATIVE),
        rule("みません", listOf("む"), POLITE, NEGATIVE),
        rule("りません", listOf("る"), POLITE, NEGATIVE),
        rule("いません", listOf("う"), POLITE, NEGATIVE),
        rule("ません", listOf("る", "う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む"), POLITE, NEGATIVE),
        rule("きました", listOf("く"), POLITE, PAST),
        rule("ぎました", listOf("ぐ"), POLITE, PAST),
        rule("しました", listOf("す", "する"), POLITE, PAST),
        rule("ちました", listOf("つ"), POLITE, PAST),
        rule("にました", listOf("ぬ"), POLITE, PAST),
        rule("びました", listOf("ぶ"), POLITE, PAST),
        rule("みました", listOf("む"), POLITE, PAST),
        rule("りました", listOf("る"), POLITE, PAST),
        rule("いました", listOf("う"), POLITE, PAST),
        rule("ました", listOf("る"), POLITE, PAST),
        rule("きます", listOf("く"), POLITE),
        rule("ぎます", listOf("ぐ"), POLITE),
        rule("します", listOf("す", "する"), POLITE),
        rule("ちます", listOf("つ"), POLITE),
        rule("にます", listOf("ぬ"), POLITE),
        rule("びます", listOf("ぶ"), POLITE),
        rule("みます", listOf("む"), POLITE),
        rule("ります", listOf("る"), POLITE),
        rule("います", listOf("う"), POLITE),
        rule("ます", listOf("る"), POLITE),
        rule("かない", listOf("く"), NEGATIVE),
        rule("がない", listOf("ぐ"), NEGATIVE),
        rule("さない", listOf("す"), NEGATIVE),
        rule("たない", listOf("つ"), NEGATIVE),
        rule("なない", listOf("ぬ"), NEGATIVE),
        rule("ばない", listOf("ぶ"), NEGATIVE),
        rule("まない", listOf("む"), NEGATIVE),
        rule("らない", listOf("る"), NEGATIVE),
        rule("わない", listOf("う"), NEGATIVE),
        rule("なかった", listOf("ない"), PAST),
        rule("なくて", listOf("ない"), TE_FORM),
        rule("なければ", listOf("ない"), CONDITIONAL),
        rule("ない", listOf("る", "う"), NEGATIVE),
        rule("かった", listOf("い"), PAST),
        rule("くない", listOf("い"), NEGATIVE),
        rule("くて", listOf("い"), TE_FORM),
        rule("ければ", listOf("い"), CONDITIONAL),
        rule("かれる", listOf("く"), PASSIVE),
        rule("がれる", listOf("ぐ"), PASSIVE),
        rule("される", listOf("す", "する"), PASSIVE),
        rule("たれる", listOf("つ"), PASSIVE),
        rule("なれる", listOf("ぬ"), PASSIVE),
        rule("ばれる", listOf("ぶ"), PASSIVE),
        rule("まれる", listOf("む"), PASSIVE),
        rule("られる", listOf("る"), PASSIVE_OR_POTENTIAL),
        rule("われる", listOf("う"), PASSIVE),
        rule("かせる", listOf("く"), CAUSATIVE),
        rule("がせる", listOf("ぐ"), CAUSATIVE),
        rule("させる", listOf("す", "する", "る"), CAUSATIVE),
        rule("たせる", listOf("つ"), CAUSATIVE),
        rule("なせる", listOf("ぬ"), CAUSATIVE),
        rule("ばせる", listOf("ぶ"), CAUSATIVE),
        rule("ませる", listOf("む"), CAUSATIVE),
        rule("らせる", listOf("る"), CAUSATIVE),
        rule("わせる", listOf("う"), CAUSATIVE),
        rule("ける", listOf("く"), POTENTIAL),
        rule("げる", listOf("ぐ"), POTENTIAL),
        rule("せる", listOf("す"), POTENTIAL),
        rule("てる", listOf("つ"), POTENTIAL),
        rule("てる", listOf("ている"), COLLOQUIAL),
        rule("ねる", listOf("ぬ"), POTENTIAL),
        rule("べる", listOf("ぶ"), POTENTIAL),
        rule("める", listOf("む"), POTENTIAL),
        rule("れる", listOf("る"), POTENTIAL),
        rule("える", listOf("う"), POTENTIAL),
        rule("ている", listOf("て"), PROGRESSIVE),
        rule("でいる", listOf("で"), PROGRESSIVE),
        rule("でる", listOf("でいる"), COLLOQUIAL),
        rule("てしまう", listOf("て"), COMPLETIVE),
        rule("でしまう", listOf("で"), COMPLETIVE),
        rule("ちゃう", listOf("て"), COMPLETIVE, COLLOQUIAL),
        rule("じゃう", listOf("で"), COMPLETIVE, COLLOQUIAL),
        rule("こう", listOf("く"), VOLITIONAL),
        rule("ごう", listOf("ぐ"), VOLITIONAL),
        rule("そう", listOf("す"), VOLITIONAL),
        rule("とう", listOf("つ"), VOLITIONAL),
        rule("のう", listOf("ぬ"), VOLITIONAL),
        rule("ぼう", listOf("ぶ"), VOLITIONAL),
        rule("もう", listOf("む"), VOLITIONAL),
        rule("ろう", listOf("る"), VOLITIONAL),
        rule("おう", listOf("う"), VOLITIONAL),
        rule("よう", listOf("る"), VOLITIONAL),
        rule("けば", listOf("く"), CONDITIONAL),
        rule("げば", listOf("ぐ"), CONDITIONAL),
        rule("せば", listOf("す"), CONDITIONAL),
        rule("てば", listOf("つ"), CONDITIONAL),
        rule("ねば", listOf("ぬ"), CONDITIONAL),
        rule("べば", listOf("ぶ"), CONDITIONAL),
        rule("めば", listOf("む"), CONDITIONAL),
        rule("れば", listOf("る"), CONDITIONAL),
        rule("えば", listOf("う"), CONDITIONAL),
        rule("った", listOf("う", "つ", "る"), PAST),
        rule("って", listOf("う", "つ", "る"), TE_FORM),
        rule("んだ", listOf("ぬ", "ぶ", "む"), PAST),
        rule("んで", listOf("ぬ", "ぶ", "む"), TE_FORM),
        rule("いた", listOf("く"), PAST),
        rule("いて", listOf("く"), TE_FORM),
        rule("いだ", listOf("ぐ"), PAST),
        rule("いで", listOf("ぐ"), TE_FORM),
        rule("した", listOf("す", "する"), PAST),
        rule("して", listOf("す", "する"), TE_FORM),
        rule("た", listOf("る"), PAST),
        rule("て", listOf("る"), TE_FORM),
        rule("く", listOf("い"), ADVERBIAL),
        rule("んない", listOf("らない"), COLLOQUIAL),
        rule("きたい", listOf("く"), DESIDERATIVE),
        rule("ぎたい", listOf("ぐ"), DESIDERATIVE),
        rule("したい", listOf("す", "する"), DESIDERATIVE),
        rule("ちたい", listOf("つ"), DESIDERATIVE),
        rule("にたい", listOf("ぬ"), DESIDERATIVE),
        rule("びたい", listOf("ぶ"), DESIDERATIVE),
        rule("みたい", listOf("む"), DESIDERATIVE),
        rule("りたい", listOf("る"), DESIDERATIVE),
        rule("いたい", listOf("う"), DESIDERATIVE),
        rule("たい", listOf("る"), DESIDERATIVE),
    ).groupBy { it.suffix.last() }

    private val irregularForms = mapOf(
        "した" to ("する" to listOf(PAST)),
        "して" to ("する" to listOf(TE_FORM)),
        "しない" to ("する" to listOf(NEGATIVE)),
        "しよう" to ("する" to listOf(VOLITIONAL)),
        "したい" to ("する" to listOf(DESIDERATIVE)),
        "きた" to ("くる" to listOf(PAST)),
        "きて" to ("くる" to listOf(TE_FORM)),
        "きます" to ("くる" to listOf(POLITE)),
        "こない" to ("くる" to listOf(NEGATIVE)),
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
            deinflect(normalized).forEach { form ->
                results.putIfAbsent(
                    form.text,
                    JapaneseTextCandidate(
                        form.text, literal.sourceStart, literal.text.length, form.depth, form.conjugations,
                    ),
                )
            }
        }
        return results.values.toList()
    }

    private data class LiteralCandidate(
        val text: String,
        val sourceStart: Int,
    )

    private data class DeinflectedForm(
        val text: String,
        val depth: Int,
        val conjugations: List<JapaneseConjugation>,
    )

    private fun deinflect(source: String): Collection<DeinflectedForm> {
        val initial = DeinflectedForm(source, 0, emptyList())
        val discovered = linkedMapOf(source to initial)
        val queue = ArrayDeque<DeinflectedForm>().apply { add(initial) }
        while (queue.isNotEmpty() && discovered.size < MAX_FORMS_PER_LITERAL) {
            val current = queue.removeFirst()
            if (current.depth >= MAX_DEINFLECTION_DEPTH) continue
            fun discover(text: String, conjugations: List<JapaneseConjugation>) {
                if (discovered.size >= MAX_FORMS_PER_LITERAL || text in discovered) return
                // Rules unwind the surface form. Display the explanation from base to surface.
                val form = DeinflectedForm(text, current.depth + 1, (conjugations + current.conjugations).distinct())
                discovered[text] = form
                queue.add(form)
            }
            irregularForms[current.text]?.let { (text, conjugations) -> discover(text, conjugations) }
            suffixRules[current.text.last()].orEmpty().forEach { rule ->
                if (!current.text.endsWith(rule.suffix) || current.text.length <= rule.suffix.length) return@forEach
                rule.replacements.forEach { replacement ->
                    discover(current.text.dropLast(rule.suffix.length) + replacement, rule.conjugations)
                }
            }
        }
        discovered.remove(source)
        return discovered.values
    }

    private fun isJapanese(character: Int): Boolean = character in 0x3040..0x30ff ||
        character in 0x3400..0x4dbf || character in 0x4e00..0x9fff || character in 0x20000..0x323af ||
        character in 0xff66..0xff9f || character in 0xff10..0xff19 || character in 0x30..0x39 ||
        character == 0x3005
}
