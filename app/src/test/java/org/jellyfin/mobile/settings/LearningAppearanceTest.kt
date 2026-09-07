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
}
