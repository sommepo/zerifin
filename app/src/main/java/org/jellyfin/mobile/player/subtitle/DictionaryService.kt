package org.jellyfin.mobile.player.subtitle

/**
 * Looks up a resolved Japanese term.
 *
 * The phase 1 implementation is intentionally in-memory. A later implementation can replace
 * this interface with a Yomitan-compatible dictionary without coupling it to subtitle rendering.
 */
fun interface DictionaryService {
    fun lookup(term: String): DictionaryEntry?
}
