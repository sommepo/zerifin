package org.jellyfin.mobile.player.ui

import org.jellyfin.mobile.settings.LearningControlsPosition

/** Converts the movable player shortcuts between screen pixels and orientation-independent storage. */
internal object LearningControlsPositioner {
    fun toPixels(position: LearningControlsPosition, availableWidth: Int, availableHeight: Int): Pair<Float, Float> =
        position.x.coerceIn(0f, 1f) * availableWidth.coerceAtLeast(0) to
            position.y.coerceIn(0f, 1f) * availableHeight.coerceAtLeast(0)

    fun normalize(x: Float, y: Float, availableWidth: Int, availableHeight: Int): LearningControlsPosition =
        LearningControlsPosition(
            if (availableWidth > 0) (x / availableWidth).coerceIn(0f, 1f) else 0f,
            if (availableHeight > 0) (y / availableHeight).coerceIn(0f, 1f) else 0f,
        )
}
