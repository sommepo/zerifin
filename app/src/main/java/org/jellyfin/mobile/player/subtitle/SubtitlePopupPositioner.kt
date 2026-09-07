package org.jellyfin.mobile.player.subtitle

/** Pixel bounds for the subtitle word that owns a dictionary popup. */
data class PopupAnchorBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right) { "Anchor left must not exceed right" }
        require(top <= bottom) { "Anchor top must not exceed bottom" }
    }
}

/** A measured size in pixels. */
data class PopupSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0) { "Width must not be negative" }
        require(height >= 0) { "Height must not be negative" }
    }
}

enum class PopupSide {
    BELOW,
    ABOVE,
}

/** Top-left popup coordinates in the same coordinate space as the anchor and viewport. */
data class PopupPlacement(
    val x: Int,
    val y: Int,
    val side: PopupSide,
)

/**
 * Places a measured dictionary card next to its tapped subtitle word.
 *
 * The card begins slightly before the word's leading edge and prefers the space below it. If its
 * measured height would cross the viewport's bottom margin, the card flips above the word.
 * Coordinates are then clamped to the viewport. All spacing arguments are physical pixels so the
 * UI caller retains responsibility for density conversion. When a card is larger than the inset
 * viewport, its leading edge is pinned to the margin; callers can constrain or scroll the card
 * when full containment is needed.
 */
object SubtitlePopupPositioner {
    const val DEFAULT_ANCHOR_GAP_PX = 10
    const val DEFAULT_VIEWPORT_MARGIN_PX = 8
    const val DEFAULT_HORIZONTAL_LEAD_PX = 16

    @Suppress("LongParameterList")
    fun place(
        anchorBounds: PopupAnchorBounds,
        popupSize: PopupSize,
        viewportSize: PopupSize,
        viewportMarginPx: Int = DEFAULT_VIEWPORT_MARGIN_PX,
        anchorGapPx: Int = DEFAULT_ANCHOR_GAP_PX,
        horizontalLeadPx: Int = DEFAULT_HORIZONTAL_LEAD_PX,
    ): PopupPlacement {
        require(viewportMarginPx >= 0) { "Viewport margin must not be negative" }
        require(anchorGapPx >= 0) { "Anchor gap must not be negative" }
        require(horizontalLeadPx >= 0) { "Horizontal lead must not be negative" }

        val preferredX = anchorBounds.left.toLong() - horizontalLeadPx
        val belowY = anchorBounds.bottom.toLong() + anchorGapPx
        val bottomLimit = viewportSize.height.toLong() - viewportMarginPx
        val side = if (belowY + popupSize.height <= bottomLimit) {
            PopupSide.BELOW
        } else {
            PopupSide.ABOVE
        }
        val preferredY = when (side) {
            PopupSide.BELOW -> belowY
            PopupSide.ABOVE -> anchorBounds.top.toLong() - anchorGapPx - popupSize.height
        }

        return PopupPlacement(
            x = clampCoordinate(preferredX, popupSize.width, viewportSize.width, viewportMarginPx),
            y = clampCoordinate(preferredY, popupSize.height, viewportSize.height, viewportMarginPx),
            side = side,
        )
    }

    private fun clampCoordinate(
        preferred: Long,
        popupExtent: Int,
        viewportExtent: Int,
        viewportMargin: Int,
    ): Int {
        val minimum = viewportMargin.toLong()
        val maximum = (
            viewportExtent.toLong() - popupExtent - viewportMargin
            ).coerceAtLeast(minimum)
        return preferred.coerceIn(minimum, maximum).toInt()
    }
}
