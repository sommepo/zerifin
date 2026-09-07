package org.jellyfin.mobile.player.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class AnkiMiningEngineTest {
    @Test
    fun `resolves stale ids by stored names then adds ordered fields`() {
        val api = FakeAnkiApi(
            decks = mapOf(101L to "Japanese"),
            models = mapOf(202L to "Mining"),
            fields = mapOf(202L to listOf("Word", "Meaning", "Sentence")),
            addedNoteId = 303L,
        )
        val preset = preset(
            deckId = 1,
            modelId = 2,
            fields = listOf(
                AnkiFieldMapping("Word", AnkiFieldSource.WORD),
                AnkiFieldMapping("Meaning", AnkiFieldSource.DEFINITION),
                AnkiFieldMapping("Sentence", AnkiFieldSource.SUBTITLE),
            ),
            tags = setOf(" mining ", "zerifin", ""),
        )

        val result = AnkiMiningEngine(api).mine(
            preset,
            AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日は雨だった。"),
        )

        assertEquals(AnkiMineResult.Added(303L), result)
        assertEquals(202L, api.lastAdd?.modelId)
        assertEquals(101L, api.lastAdd?.deckId)
        assertEquals(listOf("昨日", "yesterday", "<b>昨日</b>は雨だった。"), api.lastAdd?.fields)
        assertEquals(setOf("mining", "zerifin"), api.lastAdd?.tags)
    }

    @Test
    fun `current ids win even when stored names have changed`() {
        val api = FakeAnkiApi(
            decks = mapOf(10L to "Renamed deck", 11L to "Japanese"),
            models = mapOf(20L to "Renamed model", 21L to "Mining"),
            fields = mapOf(20L to listOf("Word")),
            addedNoteId = 30L,
        )

        val result = AnkiMiningEngine(api).mine(
            preset(
                fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD)),
            ),
            AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日"),
        )

        assertEquals(AnkiMineResult.Added(30L), result)
        assertEquals(10L, api.lastAdd?.deckId)
        assertEquals(20L, api.lastAdd?.modelId)
    }

    @Test
    fun `blocks duplicates before calling add note`() {
        val api = defaultApi().apply { duplicateCount = 2 }

        val result = AnkiMiningEngine(api).mine(
            preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD))),
            AnkiMiningSnapshot("<昨日>", "きのう", "yesterday", "昨日"),
        )

        assertEquals(AnkiMineResult.Duplicate(2), result)
        assertEquals("&lt;昨日&gt;", api.lastDuplicateValue)
        assertNull(api.lastAdd)
    }

    @Test
    fun `duplicate check can be disabled`() {
        val api = defaultApi().apply { duplicateCount = 3 }

        val result = AnkiMiningEngine(api).mine(
            preset(
                fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD)),
                blockDuplicates = false,
            ),
            AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日"),
        )

        assertInstanceOf(AnkiMineResult.Added::class.java, result)
        assertEquals(0, api.duplicateChecks)
    }

    @Test
    fun `duplicate query failure blocks the add`() {
        val api = defaultApi().apply { duplicateCount = null }

        val result = AnkiMiningEngine(api).mine(
            preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD))),
            AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日"),
        )

        assertEquals(
            "AnkiDroid could not check for duplicate notes",
            assertInstanceOf(AnkiMineResult.Failed::class.java, result).message,
        )
        assertNull(api.lastAdd)
    }

    @Test
    fun `reports a failed add when AnkiDroid returns no note id`() {
        val api = defaultApi().copy(addedNoteId = null)

        val result = AnkiMiningEngine(api).mine(
            preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD))),
            AnkiMiningSnapshot("昨日", "きのう", "yesterday", "昨日"),
        )

        assertEquals(
            "AnkiDroid could not add the note",
            assertInstanceOf(AnkiMineResult.Failed::class.java, result).message,
        )
    }

    @Test
    fun `fails safely when configured collection or live first field is unavailable`() {
        val missingDeck = defaultApi().copy(decks = mapOf(99L to "Other"))
        val missingDeckResult = AnkiMiningEngine(missingDeck).mine(
            preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD))),
            AnkiMiningSnapshot("昨日", null, "yesterday", "昨日"),
        )
        assertEquals(
            "The configured Anki deck no longer exists",
            assertInstanceOf(AnkiMineResult.Failed::class.java, missingDeckResult).message,
        )

        val renamedField = defaultApi().copy(fields = mapOf(20L to listOf("Renamed")))
        val renamedFieldResult = AnkiMiningEngine(renamedField).mine(
            preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD))),
            AnkiMiningSnapshot("昨日", null, "yesterday", "昨日"),
        )
        assertEquals(
            "The first Anki field cannot be empty",
            assertInstanceOf(AnkiMineResult.Failed::class.java, renamedFieldResult).message,
        )
    }

    @Test
    fun `popup duplicate checks use live text mapping without adding notes or media`() {
        val api = defaultApi().apply { duplicateCount = 1 }
        val engine = AnkiMiningEngine(api)
        val preset = preset(fields = listOf(AnkiFieldMapping("Word", AnkiFieldSource.WORD)), blockDuplicates = false)
        val snapshot = AnkiMiningSnapshot("<昨日>", null, "yesterday", "昨日")

        assertEquals(AnkiDuplicateStatus.EXISTS, engine.checkDuplicate(preset, snapshot))
        assertEquals("&lt;昨日&gt;", api.lastDuplicateValue)
        api.duplicateCount = 0
        assertEquals(AnkiDuplicateStatus.NEW, engine.checkDuplicate(preset, snapshot))
        api.duplicateCount = null
        assertEquals(AnkiDuplicateStatus.UNKNOWN, engine.checkDuplicate(preset, snapshot))
        assertNull(api.lastAdd)
        assertEquals(emptyList<AnkiMediaFile>(), api.imports)
    }

    @Test
    fun `imports mapped media only once and writes returned filenames in playable markup`() {
        val fieldNames = listOf("Word", "Audio", "Second audio", "Picture", "Sentence audio")
        val api = defaultApi().copy(fields = mapOf(20L to fieldNames))
        val wordAudio = AnkiMediaFile(File("word.wav"), "audio/wav")
        val image = AnkiMediaFile(File("frame.png"), "image/png")
        val sentenceAudio = AnkiMediaFile(File("sentence.wav"), "audio/wav")
        val mapping = listOf(
            AnkiFieldMapping("Word", AnkiFieldSource.WORD),
            AnkiFieldMapping("Audio", AnkiFieldSource.WORD_AUDIO),
            AnkiFieldMapping("Second audio", AnkiFieldSource.WORD_AUDIO),
            AnkiFieldMapping("Picture", AnkiFieldSource.IMAGE),
            AnkiFieldMapping("Sentence audio", AnkiFieldSource.SENTENCE_AUDIO),
        )
        val result = AnkiMiningEngine(api).mine(
            preset(fields = mapping),
            AnkiMiningSnapshot(
                "昨日",
                null,
                "yesterday",
                "昨日",
                wordAudio = wordAudio,
                image = image,
                sentenceAudio = sentenceAudio
            ),
        )

        assertInstanceOf(AnkiMineResult.Added::class.java, result)
        assertEquals(listOf(wordAudio, image, sentenceAudio), api.imports)
        assertEquals(
            listOf(
                "昨日",
                "[sound:imported_word.wav]",
                "[sound:imported_word.wav]",
                "<img src=\"imported_frame.png\">",
                "[sound:imported_sentence.wav]",
            ),
            api.lastAdd?.fields,
        )
    }

    @Test
    fun `checks duplicates before media import and does not add a partially imported note`() {
        val api = defaultApi().copy(fields = mapOf(20L to listOf("Word", "Audio")))
        val preset = preset(
            fields = listOf(
                AnkiFieldMapping("Word", AnkiFieldSource.WORD),
                AnkiFieldMapping("Audio", AnkiFieldSource.WORD_AUDIO),
            )
        )
        val snapshot =
            AnkiMiningSnapshot("昨日", null, "yesterday", "昨日", wordAudio = AnkiMediaFile(File("word.wav"), "audio/wav"))
        api.duplicateCount = 2

        assertEquals(AnkiMineResult.Duplicate(2), AnkiMiningEngine(api).mine(preset, snapshot))
        assertEquals(emptyList<AnkiMediaFile>(), api.imports)

        api.duplicateCount = 0
        api.failMediaImport = true
        assertInstanceOf(AnkiMineResult.Failed::class.java, AnkiMiningEngine(api).mine(preset, snapshot))
        assertNull(api.lastAdd)
    }

    @Test
    fun `unmapped media is not imported and missing optional media stays blank`() {
        val api = defaultApi().copy(fields = mapOf(20L to listOf("Word", "Audio")))
        val result = AnkiMiningEngine(api).mine(
            preset(
                fields = listOf(
                    AnkiFieldMapping("Word", AnkiFieldSource.WORD),
                    AnkiFieldMapping("Audio", AnkiFieldSource.SENTENCE_AUDIO),
                    AnkiFieldMapping("Removed", AnkiFieldSource.WORD_AUDIO),
                )
            ),
            AnkiMiningSnapshot("昨日", null, "yesterday", "昨日", wordAudio = AnkiMediaFile(File("word.wav"), "audio/wav")),
        )

        assertInstanceOf(AnkiMineResult.Added::class.java, result)
        assertEquals(listOf("昨日", ""), api.lastAdd?.fields)
        assertEquals(emptyList<AnkiMediaFile>(), api.imports)
    }

    private fun defaultApi() = FakeAnkiApi(
        decks = mapOf(10L to "Japanese"),
        models = mapOf(20L to "Mining"),
        fields = mapOf(20L to listOf("Word")),
        addedNoteId = 30L,
    )

    private fun preset(
        deckId: Long = 10,
        modelId: Long = 20,
        fields: List<AnkiFieldMapping>,
        tags: Set<String> = emptySet(),
        blockDuplicates: Boolean = true,
    ) = AnkiMiningPreset(
        deckId = deckId,
        deckName = "Japanese",
        modelId = modelId,
        modelName = "Mining",
        fields = fields,
        tags = tags,
        blockDuplicates = blockDuplicates,
    )

    private data class AddCall(
        val modelId: Long,
        val deckId: Long,
        val fields: List<String>,
        val tags: Set<String>,
    )

    private data class FakeAnkiApi(
        val decks: Map<Long, String>?,
        val models: Map<Long, String>?,
        val fields: Map<Long, List<String>>,
        val addedNoteId: Long?,
    ) : AnkiApi {
        var duplicateCount: Int? = 0
        var duplicateChecks = 0
        var lastDuplicateValue: String? = null
        var lastAdd: AddCall? = null
        val imports = mutableListOf<AnkiMediaFile>()
        var failMediaImport = false

        override fun isAvailable(): Boolean = true

        override fun getDeckList(): Map<Long, String>? = decks

        override fun getModelList(): Map<Long, String>? = models

        override fun getFieldList(modelId: Long): List<String>? = fields[modelId]

        override fun countDuplicateNotes(modelId: Long, firstFieldValue: String): Int? {
            duplicateChecks++
            lastDuplicateValue = firstFieldValue
            return duplicateCount
        }

        override fun importMedia(media: AnkiMediaFile): String? {
            imports += media
            return if (failMediaImport) null else "imported_${media.file.name}"
        }

        override fun addNote(
            modelId: Long,
            deckId: Long,
            fields: List<String>,
            tags: Set<String>,
        ): Long? {
            lastAdd = AddCall(modelId, deckId, fields, tags)
            return addedNoteId
        }
    }
}
