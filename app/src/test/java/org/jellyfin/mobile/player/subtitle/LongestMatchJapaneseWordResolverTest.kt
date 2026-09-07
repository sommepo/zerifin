package org.jellyfin.mobile.player.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LongestMatchJapaneseWordResolverTest {
    private val resolver = LongestMatchJapaneseWordResolver(
        listOf("困る", "困", "言う", "昨日", "会う"),
    )

    @Test
    fun `tap on either character resolves longest spanning term`() {
        val subtitle = "そんなこと言われても困るよ"
        val wordStart = subtitle.indexOf("困る")

        assertEquals("困る", resolver.resolve(subtitle, wordStart))
        assertEquals("困る", resolver.resolve(subtitle, wordStart + 1))
    }

    @Test
    fun `tap outside known term returns null`() {
        assertNull(resolver.resolve("そんなこと言われても困るよ", 0))
    }

    @Test
    fun `invalid offset returns null`() {
        assertNull(resolver.resolve("困る", -1))
        assertNull(resolver.resolve("困る", 2))
    }

    @Test
    fun `later occurrence containing tap is resolved`() {
        assertEquals("会う", resolver.resolve("会うならまた会う", 7))
    }
}
