package org.jellyfin.mobile.player.subtitle

/**
 * Phase 1 resolver that returns the longest known term spanning the tapped character.
 */
class LongestMatchJapaneseWordResolver(knownTerms: Collection<String>) : JapaneseWordResolver {
    private val knownTerms = knownTerms
        .filter(String::isNotEmpty)
        .distinct()
        .sortedWith(compareByDescending<String>(String::length).thenBy { it })

    override fun resolve(subtitleText: String, tappedCharacterOffset: Int): String? {
        if (tappedCharacterOffset !in subtitleText.indices) return null

        return knownTerms.firstOrNull { term ->
            var matchStart = subtitleText.indexOf(term)
            while (matchStart >= 0) {
                if (tappedCharacterOffset in matchStart until matchStart + term.length) {
                    return@firstOrNull true
                }
                matchStart = subtitleText.indexOf(term, startIndex = matchStart + 1)
            }
            false
        }
    }
}
