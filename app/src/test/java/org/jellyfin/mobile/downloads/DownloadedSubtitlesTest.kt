package org.jellyfin.mobile.downloads

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DownloadedSubtitlesTest {
    @Test
    fun `file names round-trip the stream index`() {
        assertEquals(4, DownloadedSubtitles.streamIndex(DownloadedSubtitles.fileName(4)))
        assertNull(DownloadedSubtitles.streamIndex("primary.webp"))
        assertNull(DownloadedSubtitles.streamIndex("zerifin-subtitle-x.vtt"))
    }

    @Test
    fun `keeps Japanese, English and untagged text subtitles only`() {
        val streams = listOf(
            stream(2, "jpn", text = true),
            stream(3, "eng", text = true),
            stream(4, null, text = true),
            stream(5, "fre", text = true),
            stream(6, "jpn", text = false),
        )

        assertEquals(listOf(2, 3, 4), DownloadedSubtitles.wanted(streams).map { it.index })
    }

    private fun stream(index: Int, language: String?, text: Boolean): MediaStream = mockk {
        every { this@mockk.index } returns index
        every { this@mockk.language } returns language
        every { type } returns MediaStreamType.SUBTITLE
        every { isTextSubtitleStream } returns text
    }
}
