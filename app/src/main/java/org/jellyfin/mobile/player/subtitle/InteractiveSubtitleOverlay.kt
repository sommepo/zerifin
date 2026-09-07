package org.jellyfin.mobile.player.subtitle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.CaptioningManager
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Renders Media3 text cues and maps touches on their glyphs back to subtitle character offsets.
 *
 * This view intentionally handles only text cues. Bitmap subtitles and the experimental libass
 * renderer continue through Media3's standard subtitle view.
 */
@Suppress("TooManyFunctions")
class InteractiveSubtitleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private companion object {
        const val EDGE_OFFSET_DP = 1f
        const val EDGE_RADIUS_DP = 2f
        const val TEXT_HIT_SLOP_DP = 8
        val LOOKUP_HIGHLIGHT_COLOR: Int = Color.rgb(77, 83, 86)
    }

    var isSubtitleInteractionEnabled: () -> Boolean = { true }
    var onSubtitleTapped: ((tap: SubtitleTap) -> Boolean)? = null
    var onPlayerGesture: ((event: MotionEvent) -> Unit)? = null

    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val textHitSlop = TEXT_HIT_SLOP_DP * resources.displayMetrics.density
    private val defaultTypeface: Typeface? = typeface
    private var captionStyle = CaptionStyleCompat.DEFAULT
    private var captionFontScale = 1f
    private var subtitleViewport: View? = null
    private val viewportLayoutChangeListener =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateViewportTextSize(view.height)
        }
    private var pressedSubtitleTap: SubtitleTap? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var currentCueText: CharSequence = ""
    private var cuesFrozen = false
    private var pendingText: CharSequence? = null
    private var lookupHighlightStart: Int? = null
    private var lookupHighlightLength = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (subtitleViewport == null) setSubtitleViewport(parent as? View)
        refreshCaptionPreferences()
    }

    override fun onDetachedFromWindow() {
        subtitleViewport?.removeOnLayoutChangeListener(viewportLayoutChangeListener)
        subtitleViewport = null
        super.onDetachedFromWindow()
    }

    /** Use the actual video content rectangle even when this touch view lives above PlayerView. */
    fun setSubtitleViewport(viewport: View?) {
        if (subtitleViewport === viewport) return
        subtitleViewport?.removeOnLayoutChangeListener(viewportLayoutChangeListener)
        subtitleViewport = viewport
        viewport?.addOnLayoutChangeListener(viewportLayoutChangeListener)
        viewport?.post {
            if (subtitleViewport === viewport) updateViewportTextSize(viewport.height)
        }
    }

    /**
     * Mirror Media3's use of enabled Android caption preferences for the replacement text view.
     */
    fun refreshCaptionPreferences() {
        val captioningManager =
            (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)
                ?.takeIf(CaptioningManager::isEnabled)
        captionStyle = captioningManager
            ?.let { CaptionStyleCompat.createFromCaptionStyle(it.userStyle) }
            ?: CaptionStyleCompat.DEFAULT

        captionFontScale = captioningManager?.fontScale ?: 1f
        setTextColor(captionStyle.foregroundColor)
        typeface = captionStyle.typeface ?: defaultTypeface
        updateViewportTextSize(subtitleViewport?.height ?: 0)
        applyCaptionEdgeStyle()
        render(currentCueText)
    }

    private fun updateViewportTextSize(viewportHeight: Int) {
        if (viewportHeight <= 0) return

        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * viewportHeight * captionFontScale,
        )
    }

    /**
     * Submit the active Media3 cues and return whether this view is rendering interactive text.
     */
    fun submitCues(cues: List<Cue>): Boolean {
        val cueText = SpannableStringBuilder().apply {
            cues.asSequence()
                .mapNotNull(Cue::text)
                .filterNot(CharSequence::isBlank)
                .forEachIndexed { index, text ->
                    if (index > 0) append('\n')
                    append(text)
                }
        }

        if (cuesFrozen) {
            pendingText = cueText
        } else {
            render(cueText)
        }
        return isVisible
    }

    /**
     * Freeze the displayed sentence while lookup UI is open. Unfreezing applies the newest cue.
     */
    fun setCuesFrozen(frozen: Boolean): Boolean {
        if (cuesFrozen == frozen) return isVisible

        cuesFrozen = frozen
        if (frozen) {
            pendingText = null
        } else {
            pendingText?.let(::render)
            pendingText = null
        }
        return isVisible
    }

    /** Highlight the source characters resolved by the active dictionary lookup. */
    fun setLookupHighlight(sourceStart: Int, sourceLength: Int) {
        lookupHighlightStart = sourceStart.takeIf { sourceLength > 0 }
        lookupHighlightLength = sourceLength.coerceAtLeast(0)
        render(currentCueText)
    }

    fun clearLookupHighlight() {
        if (lookupHighlightStart == null) return
        lookupHighlightStart = null
        lookupHighlightLength = 0
        render(currentCueText)
    }

    /** Re-measure an active tap after text size, wrapping, or player geometry changes. */
    fun characterBoundsFor(subtitleText: String, characterOffset: Int): PopupAnchorBounds? {
        if (text.toString() != subtitleText || characterOffset !in subtitleText.indices) return null
        val textLayout = layout ?: return null
        val line = textLayout.getLineForOffset(characterOffset)
        if (characterOffset !in textLayout.getLineStart(line) until textLayout.getLineVisibleEnd(line)) {
            return null
        }
        return characterBoundsAt(textLayout, line, characterOffset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> beginSubtitleGesture(event)
        MotionEvent.ACTION_MOVE -> continueSubtitleGesture(event)
        MotionEvent.ACTION_UP -> finishSubtitleGesture(event)
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_POINTER_DOWN,
        -> cancelSubtitleGesture(event)
        else -> forwardPlayerGesture(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginSubtitleGesture(event: MotionEvent): Boolean {
        if (!isSubtitleInteractionEnabled()) return false

        val hit = characterHitAt(event.x, event.y) ?: return false
        pressedSubtitleTap = SubtitleTap(
            subtitleText = text.toString(),
            characterOffset = hit.offset,
            characterBounds = hit.bounds,
        )
        touchDownX = event.x
        touchDownY = event.y
        isPressed = true
        onPlayerGesture?.invoke(event)
        return true
    }

    private fun continueSubtitleGesture(event: MotionEvent): Boolean {
        onPlayerGesture?.invoke(event)
        if (shouldCancelSubtitleTap(event)) clearPressedSubtitle()
        return true
    }

    private fun shouldCancelSubtitleTap(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) return true
        if (event.eventTime - event.downTime >= longPressTimeoutMs) return true
        return abs(event.x - touchDownX) > touchSlop ||
            abs(event.y - touchDownY) > touchSlop
    }

    private fun finishSubtitleGesture(event: MotionEvent): Boolean {
        val tap = pressedSubtitleTap
        clearPressedSubtitle()
        val lookupHandled = handleLookupIfEligible(event, tap)
        if (lookupHandled) {
            cancelPlayerGesture(event)
            performClick()
        } else {
            onPlayerGesture?.invoke(event)
        }
        return true
    }

    private fun handleLookupIfEligible(
        event: MotionEvent,
        tap: SubtitleTap?,
    ): Boolean {
        if (event.eventTime - event.downTime >= longPressTimeoutMs) return false
        if (tap == null) return false
        return onSubtitleTapped?.invoke(tap) == true
    }

    private fun cancelSubtitleGesture(event: MotionEvent): Boolean {
        onPlayerGesture?.invoke(event)
        clearPressedSubtitle()
        return true
    }

    private fun forwardPlayerGesture(event: MotionEvent): Boolean {
        onPlayerGesture?.invoke(event)
        return true
    }

    private fun render(cueText: CharSequence) {
        currentCueText = cueText
        text = applyCaptionStyling(cueText)
        isVisible = cueText.isNotBlank()
    }

    private fun applyCaptionStyling(cueText: CharSequence): CharSequence =
        SpannableStringBuilder(cueText).apply {
            if (isNotEmpty() && Color.alpha(captionStyle.backgroundColor) != 0) {
                setSpan(
                    BackgroundColorSpan(captionStyle.backgroundColor),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            lookupHighlightStart?.let { start ->
                val end = (start + lookupHighlightLength).coerceAtMost(length)
                if (start in 0 until end) {
                    setSpan(
                        BackgroundColorSpan(LOOKUP_HIGHLIGHT_COLOR),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun applyCaptionEdgeStyle() {
        val edgeOffset = EDGE_OFFSET_DP * resources.displayMetrics.density
        val edgeRadius = EDGE_RADIUS_DP * resources.displayMetrics.density
        when (captionStyle.edgeType) {
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW ->
                setShadowLayer(edgeRadius, edgeOffset, edgeOffset, captionStyle.edgeColor)
            CaptionStyleCompat.EDGE_TYPE_OUTLINE ->
                setShadowLayer(edgeRadius, 0f, 0f, captionStyle.edgeColor)
            CaptionStyleCompat.EDGE_TYPE_RAISED ->
                setShadowLayer(edgeRadius, -edgeOffset, -edgeOffset, captionStyle.edgeColor)
            CaptionStyleCompat.EDGE_TYPE_DEPRESSED ->
                setShadowLayer(edgeRadius, edgeOffset, edgeOffset, captionStyle.edgeColor)
            else -> setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun clearPressedSubtitle() {
        pressedSubtitleTap = null
        isPressed = false
    }

    private fun cancelPlayerGesture(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        onPlayerGesture?.invoke(cancelEvent)
        cancelEvent.recycle()
    }

    /**
     * Convert view-local coordinates to the closest UTF-16 character whose glyph was tapped.
     */
    private fun characterHitAt(viewX: Float, viewY: Float): CharacterHit? {
        val textLayout = layout ?: return null
        val localX = viewX - totalPaddingLeft + scrollX
        val localY = viewY - totalPaddingTop + scrollY
        if (localY < 0 || localY >= textLayout.height) return null

        val line = textLayout.getLineForVertical(localY.toInt())
        val lineStart = textLayout.getLineStart(line)
        val lineEnd = textLayout.getLineVisibleEnd(line)
        if (lineStart >= lineEnd) return null

        val lineLeft = min(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        val lineRight = max(textLayout.getLineLeft(line), textLayout.getLineRight(line))
        if (localX < lineLeft - textHitSlop || localX > lineRight + textHitSlop) return null

        val insertionOffset = textLayout.getOffsetForHorizontal(line, localX)
        val offset = sequenceOf(insertionOffset, insertionOffset - 1)
            .filter { it in lineStart until lineEnd }
            .minByOrNull { offset ->
                val characterLeft = textLayout.getPrimaryHorizontal(offset)
                val characterRight = textLayout.getPrimaryHorizontal(offset + 1)
                abs(localX - (characterLeft + characterRight) / 2f)
            } ?: return null
        return CharacterHit(offset, characterBoundsAt(textLayout, line, offset))
    }

    private fun characterBoundsAt(
        textLayout: android.text.Layout,
        line: Int,
        offset: Int,
    ): PopupAnchorBounds {
        val characterLeft = textLayout.getPrimaryHorizontal(offset)
        val characterRight = textLayout.getPrimaryHorizontal(offset + 1)
        val viewLeft = min(characterLeft, characterRight) + totalPaddingLeft - scrollX
        val viewRight = max(characterLeft, characterRight) + totalPaddingLeft - scrollX
        val viewTop = textLayout.getLineTop(line).toFloat() + totalPaddingTop - scrollY
        val viewBottom = textLayout.getLineBottom(line).toFloat() + totalPaddingTop - scrollY
        return PopupAnchorBounds(
            left = floor(viewLeft).toInt(),
            top = floor(viewTop).toInt(),
            right = ceil(viewRight).toInt(),
            bottom = ceil(viewBottom).toInt(),
        )
    }

    private data class CharacterHit(
        val offset: Int,
        val bounds: PopupAnchorBounds,
    )
}
