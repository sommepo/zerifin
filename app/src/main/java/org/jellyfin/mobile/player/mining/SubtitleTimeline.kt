package org.jellyfin.mobile.player.mining

import kotlin.math.max
import kotlin.math.min

data class MiningCue(val startMs: Long, val endMs: Long, val text: String)

/** Timings use the original media clock, independent of the current playback speed. */
object SubtitleTimeline {
    fun atPosition(cues: List<MiningCue>, positionMs: Long): String? = cues
        .filter { positionMs >= it.startMs && positionMs < it.endMs }
        .joinToString("\n") { it.text }.takeIf(String::isNotBlank)

    private val timing =
        Regex("^((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s+-->\\s+((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})(?:\\s.*)?$")

    fun parse(document: String): List<MiningCue> = document.replace("\r", "")
        .split(Regex("\n[ \\t]*\n"))
        .mapNotNull { block ->
            val lines = block.lines()
            val index = lines.indexOfFirst { timing.matches(it.trim()) }
            if (index < 0) return@mapNotNull null
            val match = timing.matchEntire(lines[index].trim()) ?: return@mapNotNull null
            val start = timestamp(match.groupValues[1]) ?: return@mapNotNull null
            val end = timestamp(match.groupValues[2]) ?: return@mapNotNull null
            val text = plainText(lines.drop(index + 1).joinToString("\n"))
            if (end <= start || text.isBlank()) null else MiningCue(start, end, text)
        }.sortedBy(MiningCue::startMs)

    fun matchingCue(cues: List<MiningCue>, positionMs: Long, text: String): MiningCue? = cues
        .firstOrNull { positionMs >= it.startMs && positionMs < it.endMs && normalized(it.text) == normalized(text) }

    fun translation(cue: MiningCue, english: List<MiningCue>): String? = english
        .filter {
            val overlap = min(cue.endMs, it.endMs) - max(cue.startMs, it.startMs)
            overlap > 0 && overlap * 2 >= min(cue.endMs - cue.startMs, it.endMs - it.startMs)
        }
        .map(MiningCue::text).distinct().joinToString("\n").ifBlank { null }

    fun languageMatches(language: String?, expected: String): Boolean = language
        ?.lowercase()?.substringBefore('-')?.substringBefore('_') in when (expected) {
        "ja" -> setOf("ja", "jpn", "japanese")
        "en" -> setOf("en", "eng", "english")
        else -> setOf(expected)
    }

    private fun normalized(text: String): String = plainText(text).replace(Regex("\\s+"), "")

    private fun plainText(text: String): String = text.replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&").trim()

    private fun timestamp(value: String): Long? {
        val parts = value.replace(',', '.').split(':', '.')
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        val offset = if (numbers.size == 4) 1 else 0
        if (numbers.size !in 3..4 || numbers[offset] !in 0..59 || numbers[offset + 1] !in 0..59) return null
        return numbers.takeIf { offset == 1 }?.first().orZero() * 3_600_000 +
            numbers[offset] * 60_000 + numbers[offset + 1] * 1000 + numbers[offset + 2]
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
