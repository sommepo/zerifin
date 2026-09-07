package org.jellyfin.mobile.player.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JapaneseWordAudioRequestTest {
    @Test
    fun `kanji request includes exact expression and Japanese reading`() {
        val url = JapaneseWordAudioRequest.url(DictionaryEntry("食べる", "to eat", "たべる"))!!
        assertEquals("https", url.scheme)
        assertEquals("assets.languagepod101.com", url.host)
        assertEquals("食べる", url.queryParameter("kanji"))
        assertEquals("たべる", url.queryParameter("kana"))
    }

    @Test
    fun `kana word can use its spelling as reading`() {
        val url = JapaneseWordAudioRequest.url(DictionaryEntry("ありがとう", "thanks"))!!
        assertNull(url.queryParameter("kanji"))
        assertEquals("ありがとう", url.queryParameter("kana"))
    }

    @Test
    fun `missing or non Japanese reading does not request guessed audio`() {
        assertNull(JapaneseWordAudioRequest.url(DictionaryEntry("生", "life")))
        assertNull(JapaneseWordAudioRequest.url(DictionaryEntry("食べる", "eat", "taberu")))
        assertNull(JapaneseWordAudioRequest.url(DictionaryEntry("", "")))
    }

    @Test
    fun `MPEG audio headers are recognized including ID3 metadata`() {
        val frame = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x00) + ByteArray(128)
        assertTrue(JapaneseWordAudioRequest.isValid(frame))
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 2, 0, 0)
        assertTrue(JapaneseWordAudioRequest.isValid(tag + frame))
    }

    @Test
    fun `HTML errors truncated tags and invalid MPEG headers are rejected`() {
        assertFalse(JapaneseWordAudioRequest.isValid("<html>Not found</html>".toByteArray()))
        assertFalse(JapaneseWordAudioRequest.isValid("ID3".toByteArray()))
        assertFalse(JapaneseWordAudioRequest.isValid(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0xff.toByte(), 0)))
        assertFalse(JapaneseWordAudioRequest.isValid(ByteArray(0)))
    }
}
