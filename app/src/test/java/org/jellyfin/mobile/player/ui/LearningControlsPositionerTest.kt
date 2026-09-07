package org.jellyfin.mobile.player.ui

import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.settings.LearningControlsPosition
import org.junit.jupiter.api.Test

class LearningControlsPositionerTest {
    @Test
    fun `stored position scales across player sizes`() {
        LearningControlsPositioner.toPixels(LearningControlsPosition(0.25f, 0.75f), 800, 400) shouldBe
            (200f to 300f)
    }

    @Test
    fun `normalization clamps a dragged control to the player`() {
        LearningControlsPositioner.normalize(-20f, 600f, 800, 400) shouldBe
            LearningControlsPosition(0f, 1f)
    }
}
