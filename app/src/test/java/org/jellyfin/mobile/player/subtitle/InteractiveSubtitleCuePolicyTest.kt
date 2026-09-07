package org.jellyfin.mobile.player.subtitle

import android.graphics.Bitmap
import android.text.Layout
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteractiveSubtitleCuePolicyTest {
    @Test
    fun `accepts ordinary unpositioned centered text cues`() {
        val supportedCues = listOf(
            textCue("そんなこと言われても困るよ"),
            textCue("昨日、友達に会った").buildUpon()
                .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMultiRowAlignment(Layout.Alignment.ALIGN_CENTER)
                .build(),
        )

        supportedCues.forEach { cue ->
            assertTrue(InteractiveSubtitleCuePolicy.canRenderFaithfully(listOf(cue)))
            assertEquals(
                0.08f,
                InteractiveSubtitleCuePolicy.bottomPaddingFraction(listOf(cue)),
            )
            assertTrue(
                InteractiveSubtitleCuePolicy.canRenderFaithfully(
                    CueGroup(listOf(cue), 1_000_000L),
                ),
            )
        }
    }

    @Test
    fun `accepts the normalized default WebVTT cue delivered by Media3`() {
        val cues = listOf(normalizedWebvttDefaultCue())

        assertTrue(InteractiveSubtitleCuePolicy.canRenderFaithfully(cues))
        assertEquals(0f, InteractiveSubtitleCuePolicy.bottomPaddingFraction(cues))
    }

    @Test
    fun `rejects empty or blank cue groups`() {
        assertFalse(InteractiveSubtitleCuePolicy.canRenderFaithfully(emptyList()))
        assertNull(InteractiveSubtitleCuePolicy.bottomPaddingFraction(emptyList()))
        assertFalse(InteractiveSubtitleCuePolicy.canRenderFaithfully(listOf(textCue("  \n"))))
    }

    @Test
    fun `rejects bitmap cues`() {
        val bitmapCue = Cue.Builder().setBitmap(mockk<Bitmap>()).build()

        assertFalse(InteractiveSubtitleCuePolicy.canRenderFaithfully(listOf(bitmapCue)))
    }

    @Test
    fun `rejects cue geometry that a single centered text view cannot preserve`() {
        val unsupportedCues = listOf(
            "start text alignment" to textCueBuilder().setTextAlignment(Layout.Alignment.ALIGN_NORMAL).build(),
            "end multi-row alignment" to textCueBuilder().setMultiRowAlignment(Layout.Alignment.ALIGN_OPPOSITE).build(),
            "fractional line" to textCueBuilder().setLine(0.8f, Cue.LINE_TYPE_FRACTION).build(),
            "line anchor" to textCueBuilder().setLineAnchor(Cue.ANCHOR_TYPE_END).build(),
            "horizontal position" to textCueBuilder()
                .setPosition(0.25f)
                .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                .build(),
            "position anchor" to textCueBuilder().setPositionAnchor(Cue.ANCHOR_TYPE_START).build(),
            "cue width" to textCueBuilder().setSize(0.5f).build(),
            "bitmap height" to textCueBuilder().setBitmapHeight(0.25f).build(),
            "fractional text size" to textCueBuilder()
                .setTextSize(0.08f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
                .build(),
            "window color" to textCueBuilder().setWindowColor(0xff000000.toInt()).build(),
            "vertical text" to textCueBuilder().setVerticalType(Cue.VERTICAL_TYPE_RL).build(),
            "sheared text" to textCueBuilder().setShearDegrees(12f).build(),
            "non-default z-index" to textCueBuilder().setZIndex(2).build(),
        )

        unsupportedCues.forEach { (description, cue) ->
            assertFalse(
                InteractiveSubtitleCuePolicy.canRenderFaithfully(listOf(cue)),
                description,
            )
        }
    }

    @Test
    fun `rejects a mixed group when any cue is unsupported`() {
        val cueGroup = CueGroup(
            listOf(
                textCue("ordinary"),
                textCueBuilder().setPosition(0.5f).build(),
            ),
            0L,
        )

        assertFalse(InteractiveSubtitleCuePolicy.canRenderFaithfully(cueGroup))
    }

    @Test
    fun `rejects Media3 default WebVTT stacking that the single view cannot reproduce`() {
        val cueGroup = CueGroup(
            listOf(
                normalizedWebvttDefaultCue(),
                normalizedWebvttDefaultCue().buildUpon().setLine(-2f, Cue.LINE_TYPE_NUMBER).build(),
            ),
            0L,
        )

        assertFalse(InteractiveSubtitleCuePolicy.canRenderFaithfully(cueGroup))
    }

    private fun textCue(text: String): Cue = Cue.Builder().setText(text).build()

    private fun textCueBuilder(): Cue.Builder = Cue.Builder().setText("subtitle")

    private fun normalizedWebvttDefaultCue(): Cue = textCueBuilder()
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setLine(-1f, Cue.LINE_TYPE_NUMBER)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(1f)
        .build()
}
