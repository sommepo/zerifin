package org.jellyfin.mobile.player.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.anki.AnkiDroidGateway
import java.io.File
import java.io.IOException

/**
 * Words to tint in subtitles: the Word field of the configured Anki note type plus an optional
 * imported list (one word per line). Held in memory; the imported list lives in app storage.
 */
class KnownWordsRepository private constructor(private val context: Context) {
    private val listFile = File(context.filesDir, "known-words.txt")
    private val mutex = Mutex()

    @Volatile
    private var ankiWords: Set<String> = emptySet()

    @Volatile
    private var listWords: Set<String>? = null

    @Volatile
    private var added: Set<String> = emptySet()

    val words: Set<String>
        get() = ankiWords + listWords.orEmpty() + added

    /** Re-read AnkiDroid and the imported list; call when the player opens. */
    suspend fun refresh(): Set<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            listWords = readList()
            AnkiDroidGateway.get(context).knownWordFieldValues()?.let { values ->
                ankiWords = values.mapNotNullTo(HashSet(), KnownWordMatcher::normalize)
            }
        }
        words
    }

    /** A word just mined counts as known straight away. */
    fun add(word: String) {
        KnownWordMatcher.normalize(word)?.let { added = added + it }
    }

    suspend fun importedCount(): Int = withContext(Dispatchers.IO) { readList().size }

    /** Replace the imported list with a text file the viewer chose. Returns the word count. */
    suspend fun importList(uri: Uri): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val lines = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).lineSequence().take(MAX_LIST_WORDS).toList()
            } ?: throw IOException("The word list could not be opened")
            val normalized = lines.mapNotNullTo(LinkedHashSet(), KnownWordMatcher::normalize)
            if (normalized.isEmpty()) throw IOException("The word list has no Japanese words")
            val temporary = File(listFile.parentFile, "${listFile.name}.tmp")
            temporary.writeText(normalized.joinToString("\n"), Charsets.UTF_8)
            if (!temporary.renameTo(listFile)) {
                temporary.delete()
                throw IOException("The word list could not be saved")
            }
            listWords = normalized
            normalized.size
        }
    }

    suspend fun clearList() = mutex.withLock {
        withContext(Dispatchers.IO) { listFile.delete() }
        listWords = emptySet()
    }

    private fun readList(): Set<String> = if (listFile.isFile) {
        listFile.readLines(Charsets.UTF_8).mapNotNullTo(HashSet(), KnownWordMatcher::normalize)
    } else {
        emptySet()
    }

    companion object {
        private const val MAX_LIST_WORDS = 200_000

        @Volatile
        private var instance: KnownWordsRepository? = null

        fun get(context: Context): KnownWordsRepository = instance ?: synchronized(this) {
            instance ?: KnownWordsRepository(context.applicationContext).also { instance = it }
        }
    }
}
