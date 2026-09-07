package org.jellyfin.mobile.player.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HardcodedDictionaryServiceTest {
    private val dictionary = HardcodedDictionaryService()

    @Test
    fun `prototype entries are available`() {
        assertEquals(
            "to be troubled; to be inconvenienced",
            dictionary.lookup("困る")?.definition,
        )
        assertEquals("to say", dictionary.lookup("言う")?.definition)
        assertEquals("yesterday", dictionary.lookup("昨日")?.definition)
        assertEquals("to meet", dictionary.lookup("会う")?.definition)
    }

    @Test
    fun `unknown term returns null`() {
        assertNull(dictionary.lookup("食べる"))
    }
}
