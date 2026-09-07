package org.jellyfin.mobile.player.subtitle

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DictionaryImportResult(
    val title: String,
    val entryCount: Int,
    val termCount: Int = entryCount,
    val frequencyCount: Int = 0,
)

data class DictionaryStatus(
    val title: String?,
    val entryCount: Int,
    val termCount: Int = entryCount,
    val frequencyCount: Int = 0,
    val dictionaryCount: Int = 0,
)

data class InstalledDictionary(val id: Long, val title: String, val terms: Int, val frequencies: Int, val enabled: Boolean)

/** Local dictionaries and independent reading-aware frequency indexes. */
class YomitanDictionaryRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = YomitanDictionaryDatabase(applicationContext)
    private val importMutex = Mutex()
    private val fallbackDictionary = HardcodedDictionaryService()

    suspend fun status(): DictionaryStatus = withContext(Dispatchers.IO) {
        val db = database.readableDatabase
        val terms = db.countRows("term")
        val frequencies = db.countRows("frequency")
        val titles = db.rawQuery("SELECT title FROM dictionary ORDER BY id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        DictionaryStatus(
            titles.joinToString(", ").takeIf(String::isNotEmpty),
            terms + frequencies,
            terms,
            frequencies,
            titles.size
        )
    }

    suspend fun dictionaries(): List<InstalledDictionary> = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery(
            "SELECT id, title, (SELECT COUNT(*) FROM term WHERE dictionary_id = dictionary.id), " +
                "(SELECT COUNT(*) FROM frequency WHERE dictionary_id = dictionary.id), enabled FROM dictionary ORDER BY id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    InstalledDictionary(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getInt(3),
                        cursor.getInt(4) != 0
                    )
                )
            }
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        importMutex.withLock {
            database.writableDatabase.update(
                "dictionary",
                ContentValues().apply { put("enabled", enabled) },
                "id = ?",
                arrayOf(id.toString())
            )
        }
    }

    suspend fun import(uri: Uri): DictionaryImportResult = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.delete("term_staging", null, null)
                db.delete("frequency_staging", null, null)
                val input = applicationContext.contentResolver.openInputStream(uri)
                    ?: error("The selected dictionary could not be opened")
                val summary = input.use {
                    YomitanDictionaryArchiveReader { coroutineContext.ensureActive() }.read(
                        it,
                        db
                    )
                }
                coroutineContext.ensureActive()
                installDictionary(db, summary)
                db.setTransactionSuccessful()
                DictionaryImportResult(
                    summary.title,
                    summary.termCount + summary.frequencyCount,
                    summary.termCount,
                    summary.frequencyCount
                )
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun installDictionary(db: SQLiteDatabase, summary: YomitanArchiveSummary) {
        val existingId = db.rawQuery(
            "SELECT id FROM dictionary WHERE title = ?",
            arrayOf(summary.title)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        val values = ContentValues().apply {
            put("title", summary.title)
            put("occurrence_based", summary.occurrenceBased)
        }
        val dictionaryId = existingId?.also { id ->
            val arguments = arrayOf(id.toString())
            db.update("dictionary", values, "id = ?", arguments)
            db.delete("term", "dictionary_id = ?", arguments)
            db.delete("frequency", "dictionary_id = ?", arguments)
        } ?: db.insertOrThrow("dictionary", null, values)
        db.execSQL(
            "INSERT INTO term(expression, reading, definition, score, dictionary_id) " +
                "SELECT expression, reading, definition, score, ? FROM term_staging",
            arrayOf(dictionaryId),
        )
        db.execSQL(
            "INSERT INTO frequency(expression, reading, value, display_value, dictionary_id) " +
                "SELECT expression, reading, value, display_value, ? FROM frequency_staging",
            arrayOf(dictionaryId),
        )
        db.delete("term_staging", null, null)
        db.delete("frequency_staging", null, null)
    }

    suspend fun lookup(subtitleText: String, tappedCharacterOffset: Int): DictionaryLookupResult =
        withContext(Dispatchers.IO) {
            val candidates = JapaneseTextCandidateGenerator.generate(subtitleText, tappedCharacterOffset)
            if (candidates.isEmpty()) return@withContext DictionaryLookupResult(emptyList())
            val db = database.readableDatabase
            val matches = candidates.chunked(MAX_QUERY_CANDIDATES).flatMapIndexed { batchIndex, batch ->
                coroutineContext.ensureActive()
                queryTerms(db, batch, batchIndex * MAX_QUERY_CANDIDATES)
            }.ifEmpty {
                if (db.hasTerms()) emptyList() else fallbackMatches(candidates)
            }.sortedWith(compareBy<RankedEntry> { it.candidateRank }.thenByDescending { it.dictionaryScore })
                .take(MAX_RESULTS)
            coroutineContext.ensureActive()
            val preferredFrequency = db.rawQuery(
                "SELECT title FROM dictionary WHERE enabled = 1 AND EXISTS " +
                    "(SELECT 1 FROM frequency WHERE dictionary_id = dictionary.id) ORDER BY id LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val rankedResults = matches.map { ranked -> ranked.copy(entry = withFrequencies(db, ranked.entry)) }
                .sortedWith(
                    compareBy<RankedEntry> { it.candidateRank }
                        .thenBy {
                            it.entry.frequencies.firstOrNull { frequency -> frequency.dictionaryTitle == preferredFrequency }
                                ?.sortValue ?: Double.MAX_VALUE
                        }
                        .thenByDescending { it.dictionaryScore },
                )
                .distinctBy { Triple(it.entry.term, it.entry.reading, it.entry.definition) }
                .take(DISPLAY_RESULTS)
            val matchedCandidate = rankedResults.firstOrNull()?.candidate
            DictionaryLookupResult(
                entries = rankedResults.map { ranked ->
                    ranked.entry.withMatch(ranked.candidate, subtitleText)
                },
                matchedSourceStart = matchedCandidate?.sourceStart,
                matchedSourceLength = matchedCandidate?.sourceLength,
            )
        }

    private fun queryTerms(db: SQLiteDatabase, candidates: List<JapaneseTextCandidate>, rankOffset: Int): List<RankedEntry> {
        val candidateRows = candidates.mapIndexed { index, _ -> "(?, $index)" }.joinToString(",")
        val arguments = candidates.map(JapaneseTextCandidate::text).toTypedArray()
        return db.rawQuery(
            "WITH candidate(value, candidate_rank) AS (VALUES $candidateRows), " +
                "matched AS (" +
                "SELECT term.id AS term_id, candidate_rank FROM candidate JOIN term ON expression = value " +
                "UNION ALL SELECT term.id AS term_id, candidate_rank FROM candidate JOIN term ON reading = value) " +
                "SELECT expression, reading, definition, score, MIN(candidate_rank), dictionary.title " +
                "FROM matched JOIN term ON term.id = term_id JOIN dictionary ON dictionary.id = term.dictionary_id " +
                "WHERE dictionary.enabled = 1 GROUP BY term_id ORDER BY MIN(candidate_rank), score DESC LIMIT $MAX_RESULTS",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val rank = cursor.getInt(COLUMN_CANDIDATE_RANK)
                    add(
                        RankedEntry(
                            entry = DictionaryEntry(
                                term = cursor.getString(COLUMN_EXPRESSION),
                                reading = cursor.getString(COLUMN_READING).takeIf(String::isNotBlank),
                                definition = cursor.getString(COLUMN_DEFINITION),
                                dictionaryTitle = cursor.getString(COLUMN_DICTIONARY_TITLE),
                            ),
                            candidateRank = rank + rankOffset,
                            dictionaryScore = cursor.getInt(COLUMN_SCORE),
                            candidate = candidates[rank],
                        ),
                    )
                }
            }
        }
    }

    private fun withFrequencies(db: SQLiteDatabase, entry: DictionaryEntry): DictionaryEntry {
        val frequencies = db.rawQuery(
            "SELECT dictionary.title, frequency.reading, value, display_value, occurrence_based " +
                "FROM frequency JOIN dictionary ON dictionary.id = frequency.dictionary_id " +
                "WHERE dictionary.enabled = 1 AND expression = ? AND (frequency.reading IS NULL OR frequency.reading = ?) " +
                "ORDER BY dictionary.id, frequency.id LIMIT $MAX_FREQUENCIES",
            arrayOf(entry.term, entry.reading ?: entry.term),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DictionaryFrequency(
                            dictionaryTitle = cursor.getString(0),
                            reading = if (cursor.isNull(1)) null else cursor.getString(1),
                            value = if (cursor.isNull(2)) null else cursor.getDouble(2),
                            displayValue = cursor.getString(3),
                            occurrenceBased = cursor.getInt(4) != 0,
                        ),
                    )
                }
            }.distinct()
        }
        return entry.copy(frequencies = frequencies)
    }

    private fun fallbackMatches(candidates: List<JapaneseTextCandidate>): List<RankedEntry> =
        candidates.mapIndexedNotNull { index, candidate ->
            val entry = fallbackDictionary.lookup(candidate.text)
                ?: FALLBACK_ENTRIES.firstOrNull { it.reading == candidate.text }
            entry?.let { RankedEntry(it, index, 0, candidate) }
        }

    private fun SQLiteDatabase.hasTerms(): Boolean = rawQuery("SELECT EXISTS(SELECT 1 FROM term)", null).use {
        it.moveToFirst() && it.getInt(0) != 0
    }

    private fun SQLiteDatabase.countRows(table: String): Int = rawQuery("SELECT COUNT(*) FROM $table", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private data class RankedEntry(
        val entry: DictionaryEntry,
        val candidateRank: Int,
        val dictionaryScore: Int,
        val candidate: JapaneseTextCandidate,
    )

    companion object {
        private const val MAX_RESULTS = 40
        private const val DISPLAY_RESULTS = 8
        private const val MAX_FREQUENCIES = 32
        private const val MAX_QUERY_CANDIDATES = 400
        private const val COLUMN_EXPRESSION = 0
        private const val COLUMN_READING = 1
        private const val COLUMN_DEFINITION = 2
        private const val COLUMN_SCORE = 3
        private const val COLUMN_CANDIDATE_RANK = 4
        private const val COLUMN_DICTIONARY_TITLE = 5
        private val FALLBACK_ENTRIES = listOf(
            DictionaryEntry("困る", "to be troubled; to be inconvenienced", "こまる"),
            DictionaryEntry("言う", "to say", "いう"),
            DictionaryEntry("昨日", "yesterday", "きのう"),
            DictionaryEntry("会う", "to meet", "あう"),
        )

        @Volatile
        private var instance: YomitanDictionaryRepository? = null

        fun get(context: Context): YomitanDictionaryRepository = instance ?: synchronized(this) {
            instance ?: YomitanDictionaryRepository(context).also { instance = it }
        }
    }
}
