package org.jellyfin.mobile.player.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeliveredSubtitleFormatTest {
    @Test
    fun `reads the format the server delivers rather than the source codec`() {
        assertEquals("srt", deliveredSubtitleFormat("/Videos/abc/def/Subtitles/3/0/Stream.srt?api_key=x"))
        assertEquals("ass", deliveredSubtitleFormat("/Videos/abc/def/Subtitles/2/0/Stream.ASS"))
        assertNull(deliveredSubtitleFormat("/Videos/abc/def/Subtitles/2/0/Stream"))
        assertNull(deliveredSubtitleFormat("/Videos/a.b/def/Subtitles/2/Stream"))
    }
}
