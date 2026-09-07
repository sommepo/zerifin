package org.jellyfin.mobile.player.anki

import kotlinx.serialization.Serializable
import java.io.File

const val ANKI_READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
const val ANKI_MINING_PRESET_SCHEMA_VERSION = 1

enum class AnkiAccess {
    AVAILABLE,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
}

@Serializable
enum class AnkiFieldSource {
    UNUSED,
    WORD,
    READING,
    DEFINITION,
    SUBTITLE,
    ENGLISH_SUBTITLE,
    SENTENCE_AUDIO,
    WORD_AUDIO,
    IMAGE,
    SOURCE_TITLE,
    FREQUENCY,
    ;

    val isMedia: Boolean
        get() = this == SENTENCE_AUDIO || this == WORD_AUDIO || this == IMAGE
}

data class AnkiCollectionItem(
    val id: Long,
    val name: String,
)

@Serializable
data class AnkiFieldMapping(
    val fieldName: String,
    val source: AnkiFieldSource,
)

@Serializable
data class AnkiMiningPreset(
    val schemaVersion: Int = ANKI_MINING_PRESET_SCHEMA_VERSION,
    val deckId: Long,
    val deckName: String,
    val modelId: Long,
    val modelName: String,
    val fields: List<AnkiFieldMapping>,
    val tags: Set<String> = emptySet(),
    val blockDuplicates: Boolean = true,
) {
    init {
        require(schemaVersion == ANKI_MINING_PRESET_SCHEMA_VERSION) {
            "Unsupported Anki mining preset version"
        }
        require(deckId > 0 && deckName.isNotBlank()) { "An Anki deck is required" }
        require(modelId > 0 && modelName.isNotBlank()) { "An Anki note type is required" }
        require(fields.isNotEmpty()) { "The Anki note type must have fields" }
        require(fields.all { it.fieldName.isNotBlank() }) { "Anki field names cannot be blank" }
        require(fields.map(AnkiFieldMapping::fieldName).distinct().size == fields.size) {
            "Anki field names must be unique"
        }
    }
}

data class AnkiMiningSnapshot(
    val word: String,
    val reading: String? = null,
    val definition: String,
    val subtitle: String,
    val englishSubtitle: String? = null,
    val sourceTitle: String? = null,
    val frequency: String? = null,
    val sentenceAudio: AnkiMediaFile? = null,
    val wordAudio: AnkiMediaFile? = null,
    val image: AnkiMediaFile? = null,
    val subtitleHighlightStart: Int? = null,
    val subtitleHighlightLength: Int? = null,
)

/** A short-lived file in cacheDir/anki-media, retained until mining finishes. */
data class AnkiMediaFile(val file: File, val mimeType: String)

enum class AnkiDuplicateStatus {
    NEW,
    EXISTS,
    UNKNOWN,
}

sealed interface AnkiMineResult {
    data object ConfigMissing : AnkiMineResult

    data object PermissionRequired : AnkiMineResult

    data object Unavailable : AnkiMineResult

    data class Duplicate(
        val existingNoteCount: Int,
    ) : AnkiMineResult

    data class Added(
        val noteId: Long,
    ) : AnkiMineResult

    data class Failed(
        val message: String,
    ) : AnkiMineResult
}

interface AnkiGateway {
    fun access(): AnkiAccess

    suspend fun getDecks(): List<AnkiCollectionItem>

    suspend fun getModels(): List<AnkiCollectionItem>

    suspend fun getFields(modelId: Long): List<String>

    fun loadPreset(): AnkiMiningPreset?

    fun savePreset(preset: AnkiMiningPreset)

    fun clearPreset()

    fun suggestMappings(fieldNames: List<String>): List<AnkiFieldMapping>

    suspend fun mine(snapshot: AnkiMiningSnapshot): AnkiMineResult

    suspend fun checkDuplicate(snapshot: AnkiMiningSnapshot): AnkiDuplicateStatus
}
