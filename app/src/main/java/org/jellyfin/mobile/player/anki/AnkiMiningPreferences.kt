package org.jellyfin.mobile.player.anki

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores the single user-selected Anki preset in this app's existing preference file. */
class AnkiMiningPreferences private constructor(
    private val sharedPreferences: SharedPreferences,
) {
    var lastFailure: String?
        get() = sharedPreferences.getString("anki_last_failure", null)
        set(value) { sharedPreferences.edit { putString("anki_last_failure", value) } }

    fun load(): AnkiMiningPreset? = sharedPreferences.getString(PREF_ANKI_MINING_PRESET, null)
        ?.let(AnkiPresetJson::decode)

    fun save(preset: AnkiMiningPreset) {
        validatePreset(preset)
        val normalized = preset.copy(
            deckName = preset.deckName.trim(),
            modelName = preset.modelName.trim(),
            tags = preset.tags.map(String::trim).filter(String::isNotEmpty).toSortedSet(),
        )
        sharedPreferences.edit { putString(PREF_ANKI_MINING_PRESET, AnkiPresetJson.encode(normalized)) }
    }

    fun clear() {
        sharedPreferences.edit { remove(PREF_ANKI_MINING_PRESET) }
    }

    private fun validatePreset(preset: AnkiMiningPreset) {
        require(preset.schemaVersion == ANKI_MINING_PRESET_SCHEMA_VERSION) {
            "Unsupported Anki mining preset version"
        }
    }

    companion object {
        private const val PREF_ANKI_MINING_PRESET = "pref_anki_mining_preset"

        @Volatile
        private var instance: AnkiMiningPreferences? = null

        fun get(context: Context): AnkiMiningPreferences = instance ?: synchronized(this) {
            instance ?: AnkiMiningPreferences(
                context.applicationContext.getSharedPreferences(
                    "${context.packageName}_preferences",
                    Context.MODE_PRIVATE,
                ),
            ).also { instance = it }
        }
    }
}

internal object AnkiPresetJson {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(preset: AnkiMiningPreset): String = json.encodeToString(preset)

    fun decode(value: String): AnkiMiningPreset? = runCatching {
        json.decodeFromString<AnkiMiningPreset>(value)
    }.getOrNull()
}
