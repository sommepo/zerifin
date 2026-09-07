package org.jellyfin.mobile.player.subtitle

data class DictionaryEntry(
    val term: String,
    val definition: String,
    val reading: String? = null,
    val frequencies: List<DictionaryFrequency> = emptyList(),
    val dictionaryTitle: String? = null,
    val matchedSourceStart: Int? = null,
    val matchedSourceLength: Int? = null,
    val matchedText: String? = null,
    val conjugations: List<JapaneseConjugation> = emptyList(),
)

data class DictionaryFrequency(
    val dictionaryTitle: String,
    val reading: String?,
    val value: Double?,
    val displayValue: String,
    val occurrenceBased: Boolean = false,
) {
    val sortValue: Double?
        get() = value?.let { if (occurrenceBased) -it else it }
}

data class DictionaryLookupResult(
    val entries: List<DictionaryEntry>,
    val matchedSourceStart: Int? = null,
    val matchedSourceLength: Int? = null,
)

/** Keep the visible word, highlight and mined sentence attached to the same literal source span. */
internal fun DictionaryEntry.withMatch(candidate: JapaneseTextCandidate, subtitleText: String): DictionaryEntry = copy(
    matchedSourceStart = candidate.sourceStart,
    matchedSourceLength = candidate.sourceLength,
    matchedText = subtitleText.substring(candidate.sourceStart, candidate.sourceStart + candidate.sourceLength),
    conjugations = candidate.conjugations,
)
