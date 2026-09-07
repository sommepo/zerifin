package org.jellyfin.mobile.player.anki

import java.util.Locale

internal data class BuiltAnkiNote(
    val fields: List<String>,
    val firstFieldValue: String,
)

internal sealed interface AnkiNoteBuildResult {
    data class Ready(
        val note: BuiltAnkiNote,
    ) : AnkiNoteBuildResult

    data class Invalid(
        val reason: String,
    ) : AnkiNoteBuildResult
}

internal object AnkiNoteBuilder {
    fun build(
        preset: AnkiMiningPreset,
        liveFieldNames: List<String>,
        snapshot: AnkiMiningSnapshot,
        mediaNames: Map<AnkiFieldSource, String> = emptyMap(),
    ): AnkiNoteBuildResult {
        if (liveFieldNames.isEmpty()) {
            return AnkiNoteBuildResult.Invalid("The selected Anki note type has no fields")
        }

        val configuredSources = preset.fields.associate { it.fieldName to it.source }
        if (configuredSources[liveFieldNames.first()]?.isMedia == true) {
            return AnkiNoteBuildResult.Invalid("The first Anki field must contain text so duplicates can be checked")
        }
        val fields = liveFieldNames.map { fieldName ->
            val source = configuredSources[fieldName]
            if (source?.isMedia == true) {
                mediaNames[source]?.let { mediaMarkup(source, it) }.orEmpty()
            } else if (source == AnkiFieldSource.SUBTITLE) {
                highlightedSubtitle(snapshot)
            } else {
                escapeAnkiHtml(source.orEmptyValue(snapshot))
            }
        }
        if (fields.first().isBlank()) {
            return AnkiNoteBuildResult.Invalid("The first Anki field cannot be empty")
        }

        return AnkiNoteBuildResult.Ready(
            BuiltAnkiNote(
                fields = fields,
                firstFieldValue = fields.first(),
            ),
        )
    }

    private fun highlightedSubtitle(snapshot: AnkiMiningSnapshot): String {
        val text = snapshot.subtitle
        val requestedStart = snapshot.subtitleHighlightStart
        val requestedLength = snapshot.subtitleHighlightLength
        fun validSpan(start: Int?, length: Int?): Boolean = start != null && length != null &&
            start >= 0 && start <= text.length && length > 0 && length <= text.length - start &&
            safeBoundary(text, start) && safeBoundary(text, start + length)
        val start: Int
        val length: Int
        if (validSpan(requestedStart, requestedLength)) {
            start = requireNotNull(requestedStart)
            length = requireNotNull(requestedLength)
        } else {
            start = snapshot.word.takeIf(String::isNotBlank)?.let(text::indexOf) ?: -1
            length = snapshot.word.length
            if (!validSpan(start, length)) return escapeAnkiHtml(text)
        }
        return escapeAnkiHtml(text.substring(0, start)) + "<b>" +
            escapeAnkiHtml(text.substring(start, start + length)) + "</b>" +
            escapeAnkiHtml(text.substring(start + length))
    }

    private fun safeBoundary(text: String, index: Int): Boolean = index == 0 || index == text.length ||
        !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())

    private fun AnkiFieldSource?.orEmptyValue(snapshot: AnkiMiningSnapshot): String = when (this) {
        AnkiFieldSource.WORD -> snapshot.word
        AnkiFieldSource.READING -> snapshot.reading.orEmpty()
        AnkiFieldSource.DEFINITION -> snapshot.definition
        AnkiFieldSource.SUBTITLE -> snapshot.subtitle
        AnkiFieldSource.ENGLISH_SUBTITLE -> snapshot.englishSubtitle.orEmpty()
        AnkiFieldSource.SOURCE_TITLE -> snapshot.sourceTitle.orEmpty()
        AnkiFieldSource.FREQUENCY -> snapshot.frequency.orEmpty()
        AnkiFieldSource.SENTENCE_AUDIO, AnkiFieldSource.WORD_AUDIO, AnkiFieldSource.IMAGE -> ""
        AnkiFieldSource.UNUSED, null -> ""
    }

    private fun mediaMarkup(source: AnkiFieldSource, filename: String): String {
        require(filename.isNotBlank() && filename.none { it in "\\/<>\"'[]" || it.isISOControl() }) {
            "AnkiDroid returned an invalid media filename"
        }
        return if (source == AnkiFieldSource.IMAGE) {
            "<img src=\"${escapeAnkiHtml(filename)}\">"
        } else {
            "[sound:$filename]"
        }
    }
}

internal object AnkiMappingSuggester {
    fun suggest(fieldNames: List<String>): List<AnkiFieldMapping> {
        val assigned = mutableSetOf<AnkiFieldSource>()
        val suggestions = fieldNames.map { fieldName ->
            val candidate = suggestedSource(fieldName).takeUnless { it in assigned }
                ?: AnkiFieldSource.UNUSED
            if (candidate != AnkiFieldSource.UNUSED) assigned += candidate
            AnkiFieldMapping(fieldName, candidate)
        }.toMutableList()

        if (AnkiFieldSource.WORD !in assigned) {
            suggestions.indexOfFirst { it.source == AnkiFieldSource.UNUSED }
                .takeIf { it >= 0 }
                ?.let { index -> suggestions[index] = suggestions[index].copy(source = AnkiFieldSource.WORD) }
        }
        if (AnkiFieldSource.DEFINITION !in assigned) {
            suggestions.indexOfFirst { it.source == AnkiFieldSource.UNUSED }
                .takeIf { it >= 0 }
                ?.let { index -> suggestions[index] = suggestions[index].copy(source = AnkiFieldSource.DEFINITION) }
        }
        return suggestions
    }

    private fun suggestedSource(fieldName: String): AnkiFieldSource {
        val normalized = fieldName.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return when {
            normalized.containsAny("sentenceaudio", "subtitleaudio", "contextaudio") -> AnkiFieldSource.SENTENCE_AUDIO
            normalized.containsAny("audio", "sound") -> AnkiFieldSource.WORD_AUDIO
            normalized.containsAny("picture", "image", "screenshot") -> AnkiFieldSource.IMAGE
            normalized.containsAny("sentencetranslation", "englishsentence", "englishsubtitle", "translation") ->
                AnkiFieldSource.ENGLISH_SUBTITLE
            normalized.containsAny("source", "showtitle", "episodetitle") -> AnkiFieldSource.SOURCE_TITLE
            normalized.containsAny("frequency", "frequencyrank") -> AnkiFieldSource.FREQUENCY
            normalized.containsAny("reading", "pronunciation", "kana", "furigana") -> AnkiFieldSource.READING
            normalized.containsAny("definition", "meaning", "gloss", "back") ->
                AnkiFieldSource.DEFINITION
            normalized.containsAny("subtitle", "sentence", "context", "example") ->
                AnkiFieldSource.SUBTITLE
            normalized.containsAny("word", "term", "expression", "vocabulary", "vocab", "kanji", "front") ->
                AnkiFieldSource.WORD
            else -> AnkiFieldSource.UNUSED
        }
    }

    private fun String.containsAny(vararg candidates: String): Boolean = candidates.any(::contains)
}

internal fun escapeAnkiHtml(value: String): String = buildString(value.length) {
    value.replace("\r\n", "\n").replace('\r', '\n').forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            '\n' -> append("<br>")
            '\u001f' -> append(' ')
            else -> append(character)
        }
    }
}
