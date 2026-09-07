package org.jellyfin.mobile.youtube

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class YouTubeVideo(val id: String, val title: String, val channel: String = "", val durationMs: Long = 0)

@Serializable
data class YouTubeSearch(val results: List<YouTubeVideo>)

@Serializable
data class YouTubePlayback(
    val id: String,
    val title: String,
    val channel: String = "",
    val durationMs: Long = 0,
    val session: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val videoMime: String,
    val audioMime: String,
    val videoHeaders: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = emptyMap(),
    val height: Int = 720,
    val width: Int = 1280,
    val japaneseCaptions: Boolean,
    val englishCaptions: Boolean,
    val japaneseAudio: Boolean,
    val audioLanguageUnknown: Boolean = false,
    val warning: String? = null,
    val resolverUrl: String = "",
) {
    fun resource(name: String): String = "${resolverUrl.trimEnd('/')}/session/$session/$name"
}

/** Parse only video links; shared prose may contain a single supported URL. */
object YouTubeInput {
    private val identifier = Regex("[A-Za-z0-9_-]{11}")
    private val link = Regex("https?://[^\\s<>]+")

    fun videoId(input: String): String? {
        val trimmed = input.trim()
        if (identifier.matches(trimmed)) return trimmed
        val value = link.find(trimmed)?.value?.trimEnd('.', ',', ')', ']') ?: return null
        return try {
            val uri = URI(value)
            if (uri.userInfo != null || uri.port !in listOf(-1, 443, 80)) return null
            val id = when (uri.host?.lowercase()) {
                "youtu.be", "www.youtu.be" -> uri.path.trim('/')
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> {
                    val path = uri.path.trim('/').split('/')
                    if (path.size == 2 && path[0] in listOf("shorts", "live", "embed")) path[1]
                    else uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter('=')
                }
                else -> null
            }
            id?.takeIf(identifier::matches)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }
}
