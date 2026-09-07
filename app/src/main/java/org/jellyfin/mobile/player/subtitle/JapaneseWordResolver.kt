package org.jellyfin.mobile.player.subtitle

/**
 * Resolves a candidate word containing [tappedCharacterOffset] in [subtitleText].
 *
 * Offsets are UTF-16 character offsets, matching Android's text layout APIs. This boundary keeps
 * the phase 1 heuristic replaceable by a tokenizer/deinflector in a later phase.
 */
fun interface JapaneseWordResolver {
    fun resolve(subtitleText: String, tappedCharacterOffset: Int): String?
}
