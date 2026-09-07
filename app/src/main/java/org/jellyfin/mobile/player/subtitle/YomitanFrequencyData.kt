package org.jellyfin.mobile.player.subtitle

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A normalized frequency record from the public Yomitan term metadata format. */
internal data class YomitanFrequencyData(
    val reading: String?,
    val value: Double?,
    val displayValue: String,
) {
    companion object {
        fun parse(element: JsonElement): YomitanFrequencyData? {
            val wrapper = element as? JsonObject
            val reading = (wrapper?.get("reading") as? JsonPrimitive)?.contentOrNull
            val frequency = wrapper?.get("frequency") ?: element
            val valueElement = (frequency as? JsonObject)?.get("value") ?: frequency
            val primitive = valueElement as? JsonPrimitive ?: return null
            val rawValue = primitive.contentOrNull ?: return null
            if (!primitive.isString && rawValue.toDoubleOrNull() == null) return null
            val numericValue = rawValue.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val displayValue = ((frequency as? JsonObject)?.get("displayValue") as? JsonPrimitive)
                ?.contentOrNull?.takeIf(String::isNotBlank) ?: rawValue
            if (displayValue.isBlank()) return null
            return YomitanFrequencyData(reading?.takeIf(String::isNotBlank), numericValue, displayValue)
        }
    }
}
