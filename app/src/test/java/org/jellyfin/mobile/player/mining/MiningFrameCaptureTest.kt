package org.jellyfin.mobile.player.mining

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MiningFrameCaptureTest {
    @Test
    fun `bounds landscape and portrait frames without enlarging small video`() {
        assertEquals(960 to 540, MiningFrameCapture.dimensions(1920, 1080))
        assertEquals(540 to 960, MiningFrameCapture.dimensions(1080, 1920))
        assertEquals(320 to 240, MiningFrameCapture.dimensions(320, 240))
        assertEquals(1 to 960, MiningFrameCapture.dimensions(1, Int.MAX_VALUE))
    }

    @Test
    fun `unmeasured or detached video surfaces cannot allocate a bitmap`() {
        assertNull(MiningFrameCapture.dimensions(0, 1080))
        assertNull(MiningFrameCapture.dimensions(1920, 0))
        assertNull(MiningFrameCapture.dimensions(-1, 1080))
    }
}
