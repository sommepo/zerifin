package org.jellyfin.mobile.player.subtitle

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class YomitanGlossaryTextTest {
    @Test
    fun `plain definitions retain separate senses`() {
        assertEquals("cat\na small animal", render("""["cat","a small animal","cat"]"""))
    }

    @Test
    fun `structured inline words stay together and list items remain separate`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"div","content":[
            {"tag":"span","content":"a "},{"tag":"b","content":"small"}," animal",
            {"tag":"ul","content":[{"tag":"li","content":"cat"},{"tag":"li","content":"kitten"}]}
        ]}}]"""
        assertEquals("a small animal\n• cat\n• kitten", render(glossary))
    }

    @Test
    fun `glossary metadata and image paths never become definitions`() {
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"span","data":{"secret":"metadata"},"style":{"color":"red"},"content":"cat"},
            {"tag":"br"},{"tag":"img","path":"private-path.png","alt":"cat illustration"}
        ]}]"""
        val result = render(glossary)
        assertEquals("cat\ncat illustration", result)
        assertFalse(result.contains("metadata"))
        assertFalse(result.contains("private-path"))
    }

    @Test
    fun `ruby reading stays attached to the base word`() {
        assertEquals("食(た)べる", render("""[{"type":"structured-content","content":[
            {"tag":"ruby","content":["食",{"tag":"rt","content":"た"}]},"べる"
        ]}]"""))
    }

    @Test
    fun `large glossaries stay bounded`() {
        assertEquals(8_000, render("[\"${"字".repeat(12_000)}\"]").length)
    }

    private fun render(json: String): String = YomitanGlossaryText.render(Json.parseToJsonElement(json))
}
