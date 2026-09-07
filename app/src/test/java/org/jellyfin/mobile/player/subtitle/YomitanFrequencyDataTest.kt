package org.jellyfin.mobile.player.subtitle

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class YomitanFrequencyDataTest {
    @Test
    fun `numeric and numeric string ranks agree`() {
        assertEquals(YomitanFrequencyData(null, 42.0, "42"), parse("42"))
        assertEquals(YomitanFrequencyData(null, 42.0, "42"), parse("\"42\""))
    }

    @Test
    fun `reading-specific frequency retains display markers and numeric rank`() {
        val record = parse("""{"reading":"あす","frequency":{"value":125,"displayValue":"125㋕"}}""")
        assertEquals(YomitanFrequencyData("あす", 125.0, "125㋕"), record)
    }

    @Test
    fun `plain labels are displayable without inventing a rank`() {
        assertEquals(YomitanFrequencyData(null, null, "common"), parse("\"common\""))
        assertEquals(YomitanFrequencyData(null, null, "125㋕"), parse("\"125㋕\""))
    }

    @Test
    fun `numeric objects and zero occurrence counts survive`() {
        assertEquals(YomitanFrequencyData(null, 0.0, "0"), parse("""{"value":0}"""))
        assertEquals(YomitanFrequencyData("ねこ", 2.5, "2.5"), parse("""{"reading":"ねこ","frequency":2.5}"""))
    }

    @Test
    fun `invalid metadata is ignored`() {
        listOf("null", "true", "[]", "{}", "\"\"", "{\"reading\":\"ねこ\"}").forEach { assertNull(parse(it)) }
    }

    @Test
    fun `frequency mode reverses numeric ordering only for occurrence counts`() {
        val rank = DictionaryFrequency("Ranks", null, 25.0, "25")
        assertEquals(25.0, rank.sortValue)
        assertEquals(-25.0, rank.copy(occurrenceBased = true).sortValue)
        assertNull(rank.copy(value = null).sortValue)
    }

    private fun parse(json: String): YomitanFrequencyData? = YomitanFrequencyData.parse(Json.parseToJsonElement(json))
}
