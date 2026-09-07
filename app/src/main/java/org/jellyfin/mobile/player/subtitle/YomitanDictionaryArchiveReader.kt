package org.jellyfin.mobile.player.subtitle

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

internal data class YomitanArchiveSummary(
    val title: String,
    val termCount: Int,
    val frequencyCount: Int,
    val occurrenceBased: Boolean,
)

/** Streams dictionary banks into transaction-local staging tables without retaining whole banks. */
internal class YomitanDictionaryArchiveReader(private val checkCancellation: () -> Unit) {
    private var title: String? = null
    private var termCount = 0
    private var frequencyCount = 0
    private var occurrenceBased = false
    private var totalBytes = 0L

    fun read(input: InputStream, db: SQLiteDatabase): YomitanArchiveSummary {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                checkCancellation()
                val name = entry.name.substringAfterLast('/')
                when {
                    name == "index.json" -> reader(zip).use(::readIndex)
                    TERM_BANK.matches(name) -> reader(zip).use { readTerms(it, db) }
                    FREQUENCY_BANK.matches(name) -> reader(zip).use { readFrequencies(it, db) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        check(termCount + frequencyCount > 0) { "This ZIP contains no supported Yomitan terms or frequencies" }
        return YomitanArchiveSummary(
            title = checkNotNull(title?.takeIf(String::isNotBlank)) { "Dictionary index is missing a title" },
            termCount = termCount,
            frequencyCount = frequencyCount,
            occurrenceBased = occurrenceBased,
        )
    }

    private fun reader(input: InputStream): JsonReader {
        val boundedInput = object : FilterInputStream(input) {
            override fun close() = Unit

            override fun read(): Int = `in`.read().also { if (it >= 0) countBytes(1) }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { if (it > 0) countBytes(it) }
        }
        return JsonReader(InputStreamReader(boundedInput, Charsets.UTF_8))
    }

    private fun countBytes(count: Int) {
        totalBytes += count
        check(totalBytes <= MAX_ARCHIVE_BYTES) { "Dictionary exceeds the import size limit" }
    }

    private fun readIndex(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = reader.readString()
                "frequencyMode" -> occurrenceBased = reader.readString() == "occurrence-based"
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readTerms(reader: JsonReader, db: SQLiteDatabase) {
        db.compileStatement(
            "INSERT INTO term_staging(expression, reading, definition, score) VALUES (?, ?, ?, ?)"
        ).use { insert ->
            reader.beginArray()
            while (reader.hasNext()) {
                checkCancellation()
                reader.beginArray()
                val expression = reader.readString()
                val reading = reader.readString()
                repeat(2) { reader.skipValue() }
                val score = reader.readString().toLongOrNull() ?: 0L
                val definition = YomitanGlossaryText.render(readValue(reader))
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                if (expression.isNotBlank() && definition.isNotBlank()) {
                    insert.bindString(1, expression)
                    insert.bindString(2, reading)
                    insert.bindString(3, definition)
                    insert.bindLong(4, score)
                    insert.executeInsert()
                    termCount++
                    checkCount()
                }
            }
            reader.endArray()
        }
    }

    private fun readFrequencies(reader: JsonReader, db: SQLiteDatabase) {
        db.compileStatement(
            "INSERT INTO frequency_staging(expression, reading, value, display_value) VALUES (?, ?, ?, ?)"
        ).use { insert ->
            reader.beginArray()
            while (reader.hasNext()) {
                checkCancellation()
                reader.beginArray()
                val expression = reader.readString()
                val mode = reader.readString()
                val frequency = if (mode == "freq") {
                    YomitanFrequencyData.parse(readValue(reader))
                } else {
                    reader.skipValue()
                    null
                }
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                if (expression.isNotBlank() && frequency != null) {
                    insert.clearBindings()
                    insert.bindString(1, expression)
                    frequency.reading?.let { insert.bindString(2, it) }
                    frequency.value?.let { insert.bindDouble(3, it) }
                    insert.bindString(4, frequency.displayValue)
                    insert.executeInsert()
                    frequencyCount++
                    checkCount()
                }
            }
            reader.endArray()
        }
    }

    private fun checkCount() {
        check(termCount + frequencyCount <= MAX_ENTRIES) { "Dictionary contains too many entries" }
    }

    private fun JsonReader.readString(): String = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            ""
        }
    }

    private fun readValue(reader: JsonReader, depth: Int = 0): JsonElement {
        check(depth < MAX_NESTING) { "Dictionary metadata is too deeply nested" }
        return when (reader.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> JsonPrimitive(reader.nextString())
            JsonToken.BEGIN_OBJECT -> {
                val values = mutableMapOf<String, JsonElement>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key in CONTENT_KEYS) values[key] = readValue(reader, depth + 1) else reader.skipValue()
                }
                reader.endObject()
                JsonObject(values)
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val values = buildList { while (reader.hasNext()) add(readValue(reader, depth + 1)) }
                reader.endArray()
                JsonArray(values)
            }
            else -> {
                reader.skipValue()
                JsonNull
            }
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 1_073_741_824L
        private const val MAX_ENTRIES = 1_000_000
        private const val MAX_NESTING = 32
        private val TERM_BANK = Regex("term_bank_[0-9]+\\.json")
        private val FREQUENCY_BANK = Regex("term_meta_bank_[0-9]+\\.json")
        private val CONTENT_KEYS =
            setOf("text", "content", "tag", "type", "alt", "description", "reading", "frequency", "value", "displayValue")
    }
}
