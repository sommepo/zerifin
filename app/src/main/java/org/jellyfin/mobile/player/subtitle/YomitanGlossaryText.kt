package org.jellyfin.mobile.player.subtitle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Preserves readable glossary structure without executing dictionary markup or loading resources. */
internal object YomitanGlossaryText {
    private const val MAX_LENGTH = 8_000
    private const val MAX_NESTING = 32
    private val blockTags = setOf("div", "p", "ul", "ol", "li", "table", "tr", "details", "summary", "h1", "h2", "h3")

    fun render(glossary: JsonElement): String {
        val definitions = glossary as? JsonArray ?: return ""
        return definitions.map { definition ->
            buildString { appendContent(definition, this, 0) }.trim()
        }.filter(String::isNotBlank).distinct().joinToString("\n").take(MAX_LENGTH)
    }

    private fun appendContent(element: JsonElement?, output: StringBuilder, depth: Int) {
        if (depth >= MAX_NESTING || output.length >= MAX_LENGTH) return
        when (element) {
            is JsonPrimitive -> if (element.isString) output.append(element.content.take(MAX_LENGTH - output.length))
            is JsonArray -> element.forEach { appendContent(it, output, depth + 1) }
            is JsonObject -> appendObject(element, output, depth)
            else -> Unit
        }
    }

    private fun appendObject(element: JsonObject, output: StringBuilder, depth: Int) {
        val tag = element.text("tag")
        if (tag in blockTags || tag == "br") lineBreak(output)
        when {
            tag == "br" -> Unit
            tag == "img" || element.text("type") == "image" -> {
                val remaining = (MAX_LENGTH - output.length).coerceAtLeast(0)
                output.append(
                    (element.text("alt") ?: element.text("description")).orEmpty().take(remaining),
                )
            }
            else -> {
                if (tag == "li") output.append("• ")
                if (tag == "rt") output.append('(')
                appendContent(element["content"] ?: element["text"], output, depth + 1)
                if (tag == "rt") output.append(')')
            }
        }
        if (tag in blockTags) lineBreak(output)
        if (tag == "td" || tag == "th") output.append('\t')
    }

    private fun lineBreak(output: StringBuilder) {
        if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
