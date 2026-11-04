package org.jellyfin.mobile.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LearningAppearanceTest {
    @Test
    fun `system mode follows both device themes and explicit choices override them`() {
        assertFalse(LearningPalette.usesDark(LookupTheme.SYSTEM, false))
        assertTrue(LearningPalette.usesDark(LookupTheme.SYSTEM, true))
        listOf(false, true).forEach { systemDark ->
            assertTrue(LearningPalette.usesDark(LookupTheme.DARK, systemDark))
            assertFalse(LearningPalette.usesDark(LookupTheme.LIGHT, systemDark))
        }
    }

    @Test
    fun `per-show blur areas survive encoding and reject damaged values`() {
        val area = BlurArea(0.1f, 0.7f, 0.8f, 0.2f)

        org.junit.jupiter.api.Assertions.assertEquals(area, BlurAreaCodec.decode(BlurAreaCodec.encode(area)))
        org.junit.jupiter.api.Assertions.assertNull(BlurAreaCodec.decode("0.1,x,0.8,0.2"))
        org.junit.jupiter.api.Assertions.assertNull(BlurAreaCodec.decode(null))
        org.junit.jupiter.api.Assertions.assertEquals(0.05f, BlurAreaCodec.decode("0,0,0,2")?.width)
    }
}
