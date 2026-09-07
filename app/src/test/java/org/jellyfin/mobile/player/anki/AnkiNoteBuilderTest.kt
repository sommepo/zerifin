package org.jellyfin.mobile.player.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnkiNoteBuilderTest {
    @Test
    fun `builds exact live field order and escapes all user text as html`() {
        val preset = preset(
            fields = listOf(
                AnkiFieldMapping("Meaning", AnkiFieldSource.DEFINITION),
                AnkiFieldMapping("Expression", AnkiFieldSource.WORD),
                AnkiFieldMapping("Unused", AnkiFieldSource.UNUSED),
                AnkiFieldMapping("Sentence", AnkiFieldSource.SUBTITLE),
                AnkiFieldMapping("Reading", AnkiFieldSource.READING),
            ),
        )

        val result = AnkiNoteBuilder.build(
            preset = preset,
            liveFieldNames = listOf("Expression", "Reading", "Meaning", "Sentence", "Unused"),
            snapshot = AnkiMiningSnapshot(
                word = "<昨日 & 今日>",
                reading = "\"きのう\"",
                definition = "yesterday\r\nprevious day",
                subtitle = "Jack's subtitle",
            ),
        )

        val note = assertInstanceOf(AnkiNoteBuildResult.Ready::class.java, result).note
        assertEquals(
            listOf(
                "&lt;昨日 &amp; 今日&gt;",
                "&quot;きのう&quot;",
                "yesterday<br>previous day",
                "Jack&#39;s subtitle",
                "",
            ),
            note.fields,
        )
        assertEquals(note.fields.first(), note.firstFieldValue)
    }

    @Test
    fun `rejects a blank first live field`() {
        val result = AnkiNoteBuilder.build(
            preset = preset(
                fields = listOf(
                    AnkiFieldMapping("Front", AnkiFieldSource.UNUSED),
                    AnkiFieldMapping("Back", AnkiFieldSource.DEFINITION),
                ),
            ),
            liveFieldNames = listOf("Front", "Back"),
            snapshot = AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日です"),
        )

        assertEquals(
            "The first Anki field cannot be empty",
            assertInstanceOf(AnkiNoteBuildResult.Invalid::class.java, result).reason,
        )
    }

    @Test
    fun `suggests common fields once and falls back for unknown names`() {
        assertEquals(
            listOf(
                AnkiFieldMapping("Expression", AnkiFieldSource.WORD),
                AnkiFieldMapping("Kana", AnkiFieldSource.READING),
                AnkiFieldMapping("Meaning", AnkiFieldSource.DEFINITION),
                AnkiFieldMapping("Context", AnkiFieldSource.SUBTITLE),
                AnkiFieldMapping("Other meaning", AnkiFieldSource.UNUSED),
            ),
            AnkiMappingSuggester.suggest(
                listOf("Expression", "Kana", "Meaning", "Context", "Other meaning"),
            ),
        )
        assertEquals(
            listOf(
                AnkiFieldMapping("Question", AnkiFieldSource.WORD),
                AnkiFieldMapping("Answer", AnkiFieldSource.DEFINITION),
            ),
            AnkiMappingSuggester.suggest(listOf("Question", "Answer")),
        )
    }

    @Test
    fun `preset json round trips and rejects unsupported versions`() {
        val preset = preset(
            fields = listOf(AnkiFieldMapping("Front", AnkiFieldSource.WORD)),
        )
        assertEquals(preset, AnkiPresetJson.decode(AnkiPresetJson.encode(preset)))

        val futureVersion = AnkiPresetJson.encode(preset).replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":99",
        )
        assertNull(AnkiPresetJson.decode(futureVersion))
        assertNull(AnkiPresetJson.decode("not json"))
    }

    @Test
    fun `maps translated sentence source and frequencies while retaining old presets`() {
        val fields = listOf(
            AnkiFieldMapping("Word", AnkiFieldSource.WORD),
            AnkiFieldMapping("English", AnkiFieldSource.ENGLISH_SUBTITLE),
            AnkiFieldMapping("Title", AnkiFieldSource.SOURCE_TITLE),
            AnkiFieldMapping("Rank", AnkiFieldSource.FREQUENCY),
        )
        val preset = preset(fields)
        val result = AnkiNoteBuilder.build(
            preset,
            fields.map(AnkiFieldMapping::fieldName),
            AnkiMiningSnapshot(
                "昨日",
                null,
                "yesterday",
                "昨日",
                englishSubtitle = "Yesterday & today",
                sourceTitle = "Show <2>",
                frequency = "News: 20",
            ),
        )
        assertEquals(
            listOf("昨日", "Yesterday &amp; today", "Show &lt;2&gt;", "News: 20"),
            assertInstanceOf(AnkiNoteBuildResult.Ready::class.java, result).note.fields,
        )
        assertEquals(preset, AnkiPresetJson.decode(AnkiPresetJson.encode(preset)))
        assertEquals(1, preset.schemaVersion)
    }

    @Test
    fun `suggests specific media and translation mappings ahead of sentence or reading`() {
        assertEquals(
            listOf(
                AnkiFieldSource.WORD,
                AnkiFieldSource.SENTENCE_AUDIO,
                AnkiFieldSource.WORD_AUDIO,
                AnkiFieldSource.IMAGE,
                AnkiFieldSource.ENGLISH_SUBTITLE,
                AnkiFieldSource.SOURCE_TITLE,
                AnkiFieldSource.FREQUENCY,
            ),
            AnkiMappingSuggester.suggest(
                listOf(
                    "Expression",
                    "SentenceAudio",
                    "WordAudio",
                    "Picture",
                    "SentenceTranslation",
                    "Source",
                    "Frequency"
                ),
            ).map(AnkiFieldMapping::source),
        )
    }

    @Test
    fun `rejects media as duplicate key and unsafe imported filenames`() {
        val mediaFirst = preset(listOf(AnkiFieldMapping("Audio", AnkiFieldSource.WORD_AUDIO)))
        val snapshot = AnkiMiningSnapshot("昨日", null, "yesterday", "昨日")
        assertInstanceOf(
            AnkiNoteBuildResult.Invalid::class.java,
            AnkiNoteBuilder.build(mediaFirst, listOf("Audio"), snapshot),
        )
        val fields = listOf(
            AnkiFieldMapping("Word", AnkiFieldSource.WORD),
            AnkiFieldMapping("Picture", AnkiFieldSource.IMAGE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AnkiNoteBuilder.build(
                preset(fields),
                listOf("Word", "Picture"),
                snapshot,
                mapOf(AnkiFieldSource.IMAGE to "bad\".png")
            )
        }
    }

    private fun preset(fields: List<AnkiFieldMapping>) = AnkiMiningPreset(
        deckId = 10,
        deckName = "Japanese",
        modelId = 20,
        modelName = "Mining",
        fields = fields,
    )
}
