package org.jellyfin.mobile.player.anki

internal interface AnkiApi {
    fun isAvailable(): Boolean

    fun getDeckList(): Map<Long, String>?

    fun getModelList(): Map<Long, String>?

    fun getFieldList(modelId: Long): List<String>?

    fun countDuplicateNotes(modelId: Long, firstFieldValue: String): Int?

    fun importMedia(media: AnkiMediaFile): String?

    fun addNote(modelId: Long, deckId: Long, fields: List<String>, tags: Set<String>): Long?
}

internal class AnkiMiningEngine(
    private val api: AnkiApi,
) {
    fun mine(preset: AnkiMiningPreset, snapshot: AnkiMiningSnapshot): AnkiMineResult {
        val decks = api.getDeckList()
            ?: return AnkiMineResult.Failed("AnkiDroid did not return its deck list")
        val deckId = resolveCollectionId(preset.deckId, preset.deckName, decks)
            ?: return AnkiMineResult.Failed("The configured Anki deck no longer exists")

        val models = api.getModelList()
            ?: return AnkiMineResult.Failed("AnkiDroid did not return its note type list")
        val modelId = resolveCollectionId(preset.modelId, preset.modelName, models)
            ?: return AnkiMineResult.Failed("The configured Anki note type no longer exists")
        val liveFields = api.getFieldList(modelId)
            ?: return AnkiMineResult.Failed("AnkiDroid did not return the configured note fields")

        return when (val buildResult = AnkiNoteBuilder.build(preset, liveFields, snapshot)) {
            is AnkiNoteBuildResult.Invalid -> AnkiMineResult.Failed(buildResult.reason)
            is AnkiNoteBuildResult.Ready -> addBuiltNote(
                preset = preset,
                modelId = modelId,
                deckId = deckId,
                note = buildResult.note,
                snapshot = snapshot,
                liveFields = liveFields,
            )
        }
    }

    fun checkDuplicate(preset: AnkiMiningPreset, snapshot: AnkiMiningSnapshot): AnkiDuplicateStatus {
        val models = api.getModelList() ?: return AnkiDuplicateStatus.UNKNOWN
        val modelId = resolveCollectionId(preset.modelId, preset.modelName, models)
            ?: return AnkiDuplicateStatus.UNKNOWN
        val liveFields = api.getFieldList(modelId) ?: return AnkiDuplicateStatus.UNKNOWN
        val note = AnkiNoteBuilder.build(preset, liveFields, snapshot)
        if (note !is AnkiNoteBuildResult.Ready) return AnkiDuplicateStatus.UNKNOWN
        return when (api.countDuplicateNotes(modelId, note.note.firstFieldValue)) {
            null -> AnkiDuplicateStatus.UNKNOWN
            0 -> AnkiDuplicateStatus.NEW
            else -> AnkiDuplicateStatus.EXISTS
        }
    }

    @Suppress("LongParameterList")
    private fun addBuiltNote(
        preset: AnkiMiningPreset,
        modelId: Long,
        deckId: Long,
        note: BuiltAnkiNote,
        snapshot: AnkiMiningSnapshot,
        liveFields: List<String>,
    ): AnkiMineResult {
        if (preset.blockDuplicates) {
            val duplicateCount = api.countDuplicateNotes(modelId, note.firstFieldValue)
                ?: return AnkiMineResult.Failed("AnkiDroid could not check for duplicate notes")
            if (duplicateCount > 0) return AnkiMineResult.Duplicate(duplicateCount)
        }

        val mediaNames = linkedMapOf<AnkiFieldSource, String>()
        val requestedMedia = preset.fields
            .filter { it.fieldName in liveFields && it.source.isMedia }
            .map(AnkiFieldMapping::source)
            .distinct()
        for (source in requestedMedia) {
            val media = snapshot.mediaFor(source) ?: continue
            mediaNames[source] = api.importMedia(media)
                ?: return AnkiMineResult.Failed("AnkiDroid could not import the selected audio or picture")
        }
        val complete = AnkiNoteBuilder.build(preset, liveFields, snapshot, mediaNames)
        if (complete !is AnkiNoteBuildResult.Ready) {
            return AnkiMineResult.Failed("The Anki note could not be prepared")
        }
        val tags = preset.tags.map(String::trim).filter(String::isNotEmpty).toSet()
        val noteId = api.addNote(modelId, deckId, complete.note.fields, tags)
            ?: return AnkiMineResult.Failed("AnkiDroid could not add the note")
        return AnkiMineResult.Added(noteId)
    }
}

private fun AnkiMiningSnapshot.mediaFor(source: AnkiFieldSource): AnkiMediaFile? = when (source) {
    AnkiFieldSource.SENTENCE_AUDIO -> sentenceAudio
    AnkiFieldSource.WORD_AUDIO -> wordAudio
    AnkiFieldSource.IMAGE -> image
    else -> null
}

internal fun resolveCollectionId(
    configuredId: Long,
    configuredName: String,
    liveItems: Map<Long, String>,
): Long? {
    if (configuredId in liveItems) return configuredId
    return liveItems.entries
        .asSequence()
        .filter { it.value == configuredName }
        .map(Map.Entry<Long, String>::key)
        .minOrNull()
}
