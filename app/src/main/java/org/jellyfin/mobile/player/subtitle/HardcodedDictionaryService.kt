package org.jellyfin.mobile.player.subtitle

class HardcodedDictionaryService : DictionaryService {
    private val entries = listOf(
        DictionaryEntry("困る", "to be troubled; to be inconvenienced", "こまる"),
        DictionaryEntry("言う", "to say", "いう"),
        DictionaryEntry("昨日", "yesterday", "きのう"),
        DictionaryEntry("会う", "to meet", "あう"),
    ).associateBy(DictionaryEntry::term)

    /** Terms used by the phase 1 longest-match resolver. */
    val terms: Set<String> = entries.keys

    override fun lookup(term: String): DictionaryEntry? = entries[term]
}
