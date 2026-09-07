package org.jellyfin.mobile.player.subtitle

import android.text.Layout
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView

/**
 * Guards use of [InteractiveSubtitleOverlay] to cue shapes that it can render faithfully.
 *
 * The overlay is one centered, horizontal text layout. Any cue-level instruction that would
 * require Media3's positioning, sizing, window, vertical-text, or stacking behavior must stay on
 * the stock subtitle renderer instead.
 */
object InteractiveSubtitleCuePolicy {
    private const val CENTER_POSITION = 0.5f
    private const val FULL_SIZE = 1f

    fun canRenderFaithfully(cueGroup: CueGroup): Boolean =
        canRenderFaithfully(cueGroup.cues)

    fun canRenderFaithfully(cues: List<Cue>): Boolean =
        cues.size == 1 && isOrdinaryCenteredTextCue(cues.single())

    fun bottomPaddingFraction(cues: List<Cue>): Float? {
        if (!canRenderFaithfully(cues)) return null

        return if (cues.single().hasNormalizedWebvttDefaultLine()) {
            0f
        } else {
            SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
        }
    }

    private fun isOrdinaryCenteredTextCue(cue: Cue): Boolean =
        cue.hasOrdinaryTextContent() &&
            cue.hasCenteredAlignment() &&
            cue.hasDefaultLine() &&
            cue.hasDefaultCenteredPosition() &&
            cue.hasDefaultSizing() &&
            cue.hasDefaultPresentation()

    private fun Cue.hasOrdinaryTextContent(): Boolean =
        text?.isNotBlank() == true && bitmap == null

    private fun Cue.hasCenteredAlignment(): Boolean =
        textAlignment.isUnspecifiedOrCentered() &&
            multiRowAlignment.isUnspecifiedOrCentered()

    private fun Cue.hasDefaultSizing(): Boolean =
        (size == Cue.DIMEN_UNSET || size == FULL_SIZE) &&
            bitmapHeight == Cue.DIMEN_UNSET &&
            textSizeType == Cue.TYPE_UNSET &&
            textSize == Cue.DIMEN_UNSET

    private fun Cue.hasDefaultPresentation(): Boolean =
        !windowColorSet &&
            verticalType == Cue.TYPE_UNSET &&
            shearDegrees == 0f &&
            zIndex == 0

    /* Media3 assigns an implicit WebVTT cue to the first bottom line before onCues. */
    private fun Cue.hasDefaultLine(): Boolean {
        val isUnspecified =
            line == Cue.DIMEN_UNSET &&
                lineType == Cue.TYPE_UNSET &&
                lineAnchor == Cue.TYPE_UNSET
        return isUnspecified || hasNormalizedWebvttDefaultLine()
    }

    private fun Cue.hasNormalizedWebvttDefaultLine(): Boolean =
        line == -1f &&
            lineType == Cue.LINE_TYPE_NUMBER &&
            lineAnchor == Cue.ANCHOR_TYPE_START

    private fun Cue.hasDefaultCenteredPosition(): Boolean {
        val isUnspecified = position == Cue.DIMEN_UNSET && positionAnchor == Cue.TYPE_UNSET
        val isWebvttDefault =
            position == CENTER_POSITION && positionAnchor == Cue.ANCHOR_TYPE_MIDDLE

        return isUnspecified || isWebvttDefault
    }

    private fun Layout.Alignment?.isUnspecifiedOrCentered(): Boolean =
        this == null || this == Layout.Alignment.ALIGN_CENTER
}
