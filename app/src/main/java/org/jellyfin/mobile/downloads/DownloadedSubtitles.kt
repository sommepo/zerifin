package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.player.mining.SubtitleTimeline
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** Which subtitles a download keeps, and how their files are named. */
object DownloadedSubtitles {
    private const val PREFIX = "zerifin-subtitle-"
    private const val SUFFIX = ".vtt"

    fun fileName(streamIndex: Int): String = "$PREFIX$streamIndex$SUFFIX"

    fun streamIndex(fileName: String): Int? =
        fileName.takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            ?.removePrefix(PREFIX)?.removeSuffix(SUFFIX)?.toIntOrNull()

    /** Japanese, English and untagged text subtitles; pictures (PGS, VobSub) cannot be converted. */
    fun wanted(streams: List<MediaStream>): List<MediaStream> = streams.filter { stream ->
        stream.type == MediaStreamType.SUBTITLE && stream.isTextSubtitleStream && (
            stream.language.isNullOrBlank() || stream.language == "und" ||
                SubtitleTimeline.languageMatches(stream.language, "ja") ||
                SubtitleTimeline.languageMatches(stream.language, "en")
            )
    }
}
