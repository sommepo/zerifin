package org.jellyfin.mobile.player.subtitle

data class DictionaryEntry(
    val term: String,
    val definition: String,
    val reading: String? = null,
    val frequencies: List<DictionaryFrequency> = emptyList(),
    val dictionaryTitle: String? = null,
    val matchedSourceStart: Int? = null,
    val matchedSourceLength: Int? = null,
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
