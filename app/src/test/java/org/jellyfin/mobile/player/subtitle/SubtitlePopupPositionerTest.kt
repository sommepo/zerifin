package org.jellyfin.mobile.player.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SubtitlePopupPositionerTest {
    private val viewport = PopupSize(width = 400, height = 800)

    @Test
    fun `places card below anchor with configured lead and gap when it fits`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 170, top = 300, right = 230, bottom = 340),
            popup = PopupSize(width = 200, height = 180),
        )

        assertEquals(PopupPlacement(x = 154, y = 350, side = PopupSide.BELOW), placement)
    }

    @Test
    fun `card exactly touching bottom margin remains below`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 100, top = 550, right = 140, bottom = 582),
            popup = PopupSize(width = 120, height = 200),
        )

        assertEquals(PopupPlacement(x = 84, y = 592, side = PopupSide.BELOW), placement)
    }

    @Test
    fun `one pixel of bottom overflow flips card above`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 100, top = 550, right = 140, bottom = 583),
            popup = PopupSize(width = 120, height = 200),
        )

        assertEquals(PopupPlacement(x = 84, y = 340, side = PopupSide.ABOVE), placement)
    }

    @Test
    fun `measured card height determines whether card flips`() {
        val anchor = PopupAnchorBounds(left = 170, top = 650, right = 230, bottom = 700)

        assertEquals(PopupSide.BELOW, place(anchor, PopupSize(width = 200, height = 82)).side)
        assertEquals(PopupSide.ABOVE, place(anchor, PopupSize(width = 200, height = 83)).side)
    }

    @Test
    fun `left overflow clamps card to viewport margin`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 0, top = 100, right = 20, bottom = 120),
            popup = PopupSize(width = 200, height = 100),
        )

        assertEquals(8, placement.x)
    }

    @Test
    fun `right overflow clamps entire card inside viewport margin`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 380, top = 100, right = 400, bottom = 120),
            popup = PopupSize(width = 200, height = 100),
        )

        assertEquals(192, placement.x)
        assertEquals(392, placement.x + 200)
    }

    @Test
    fun `above placement clamps to top viewport margin when neither side fits`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 170, top = 50, right = 230, bottom = 90),
            popup = PopupSize(width = 200, height = 750),
        )

        assertEquals(PopupPlacement(x = 154, y = 8, side = PopupSide.ABOVE), placement)
    }

    @Test
    fun `oversized card pins its leading edges to viewport margin`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 170, top = 300, right = 230, bottom = 340),
            popup = PopupSize(width = 500, height = 900),
        )

        assertEquals(PopupPlacement(x = 8, y = 8, side = PopupSide.ABOVE), placement)
    }

    @Test
    fun `zero sized card still receives a stable position`() {
        val placement = place(
            anchor = PopupAnchorBounds(left = 200, top = 200, right = 200, bottom = 200),
            popup = PopupSize(width = 0, height = 0),
        )

        assertEquals(PopupPlacement(x = 184, y = 210, side = PopupSide.BELOW), placement)
    }

    @Test
    fun `caller supplied pixel spacing controls placement`() {
        val placement = SubtitlePopupPositioner.place(
            anchorBounds = PopupAnchorBounds(left = 170, top = 300, right = 230, bottom = 340),
            popupSize = PopupSize(width = 200, height = 180),
            viewportSize = viewport,
            viewportMarginPx = 12,
            anchorGapPx = 14,
            horizontalLeadPx = 20,
        )

        assertEquals(PopupPlacement(x = 150, y = 354, side = PopupSide.BELOW), placement)
    }

    @Test
    fun `invalid anchor bounds are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupAnchorBounds(left = 20, top = 10, right = 19, bottom = 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PopupAnchorBounds(left = 20, top = 12, right = 21, bottom = 11)
        }
    }

    @Test
    fun `negative measured sizes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupSize(width = -1, height = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PopupSize(width = 100, height = -1)
        }
    }

    @Test
    fun `negative pixel spacing is rejected`() {
        val anchor = PopupAnchorBounds(left = 20, top = 20, right = 30, bottom = 30)
        val popup = PopupSize(width = 100, height = 100)

        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePopupPositioner.place(anchor, popup, viewport, viewportMarginPx = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePopupPositioner.place(anchor, popup, viewport, anchorGapPx = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePopupPositioner.place(anchor, popup, viewport, horizontalLeadPx = -1)
        }
    }

    private fun place(
        anchor: PopupAnchorBounds,
        popup: PopupSize,
    ): PopupPlacement = SubtitlePopupPositioner.place(
        anchorBounds = anchor,
        popupSize = popup,
        viewportSize = viewport,
    )
}
