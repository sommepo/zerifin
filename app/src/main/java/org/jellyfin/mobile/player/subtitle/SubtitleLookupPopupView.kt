package org.jellyfin.mobile.player.subtitle

import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.anki.AnkiDuplicateStatus
import org.jellyfin.mobile.settings.LearningPalette
import org.jellyfin.mobile.settings.LookupPreferences
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SubtitleMiningState {
    IDLE,
    ADDING,
    ADDED,
    DUPLICATE,
    ERROR,
}

internal data class SubtitleMiningActionPresentation(
    val glyph: String,
    val isEnabled: Boolean,
    val showBook: Boolean = false,
)

internal fun SubtitleMiningState.actionPresentation(status: AnkiDuplicateStatus = AnkiDuplicateStatus.UNKNOWN): SubtitleMiningActionPresentation = when (this) {
    SubtitleMiningState.IDLE -> SubtitleMiningActionPresentation(
        glyph = if (status == AnkiDuplicateStatus.EXISTS) "" else "+",
        isEnabled = true,
        showBook = status == AnkiDuplicateStatus.EXISTS
    )
    SubtitleMiningState.ADDING -> SubtitleMiningActionPresentation(glyph = "\u2026", isEnabled = false)
    SubtitleMiningState.ADDED,
    SubtitleMiningState.DUPLICATE,
    -> SubtitleMiningActionPresentation(glyph = "", isEnabled = false, showBook = true)
    SubtitleMiningState.ERROR -> SubtitleMiningActionPresentation(glyph = "!", isEnabled = true)
}

/**
 * A compact, non-modal dictionary popup hosted directly in the player's overlay.
 *
 * This view is expected to fill its parent. [anchorBounds] passed to the show methods must use
 * this view's local coordinate space. The visible card consumes its own touches. Touches outside
 * the card pass through so the subtitle layer can replace the lookup in one tap and the backdrop
 * beneath it can dismiss other outside taps without reopening the player controls.
 */
@Suppress("MagicNumber", "TooManyFunctions")
class SubtitleLookupPopupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private companion object {
        const val CARD_MAX_WIDTH_DP = 380
        const val CARD_MIN_WIDTH_DP = 260
        const val CARD_MAX_HEIGHT_FRACTION = 0.336f
        const val VIEWPORT_MARGIN_DP = 8
        const val ANCHOR_GAP_DP = 10
        const val HORIZONTAL_LEAD_DP = 16
        const val CARD_CORNER_RADIUS_DP = 10
        const val CARD_BORDER_WIDTH_DP = 1
        const val CARD_HORIZONTAL_PADDING_DP = 14
        const val CARD_TOP_PADDING_DP = 4
        const val CARD_BOTTOM_PADDING_DP = 6
        const val ACTION_TARGET_SIZE_DP = 48
        const val APPEAR_DURATION_MS = 120L
        const val RESULT_VERTICAL_PADDING_DP = 6
    }

    private val preferences = LookupPreferences(context)
    private val palette get() = LearningPalette.get(context, preferences.theme)
    private val CARD_COLOR get() = palette.surface
    private val BORDER_COLOR get() = palette.border
    private val PRIMARY_TEXT_COLOR get() = palette.text
    private val SECONDARY_TEXT_COLOR get() = palette.secondary
    private val SEPARATOR_COLOR get() = palette.border

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    private fun applyTheme() {
        (card.background as GradientDrawable).apply {
            setColor(CARD_COLOR)
            setStroke(dp(CARD_BORDER_WIDTH_DP), BORDER_COLOR)
        }
        fun recolor(view: View) {
            if (view is AppCompatTextView) {
                view.setTextColor(if (view.tag == "primary") PRIMARY_TEXT_COLOR else SECONDARY_TEXT_COLOR)
                view.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(palette.accent) }
            } else if (view.tag == "separator") view.setBackgroundColor(SEPARATOR_COLOR)
            if (view is ViewGroup) for (index in 0 until view.childCount) recolor(view.getChildAt(index))
        }
        recolor(card)
    }

    /** Called when the close affordance is tapped. */
    var onDismissRequested: (() -> Unit)? = null

    /** Called when the mining action beside a currently displayed dictionary entry is tapped. */
    var onMineRequested: ((DictionaryEntry) -> Unit)? = null

    var onAudioRequested: ((DictionaryEntry) -> Unit)? = null

    private var anchorBounds: RectF? = null
    private val miningButtons = IdentityHashMap<DictionaryEntry, AppCompatTextView>()
    private val miningEntries = IdentityHashMap<View, DictionaryEntry>()
    private val audioButtons = IdentityHashMap<DictionaryEntry, AppCompatTextView>()
    private val actionStates = IdentityHashMap<DictionaryEntry, SubtitleMiningState>()
    private val completedEntries = IdentityHashMap<DictionaryEntry, Boolean>()

    private val card = BoundedLinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = 0f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(CARD_CORNER_RADIUS_DP).toFloat()
            setColor(CARD_COLOR)
            setStroke(dp(CARD_BORDER_WIDTH_DP), BORDER_COLOR)
        }
        setPadding(
            dp(CARD_HORIZONTAL_PADDING_DP),
            dp(CARD_TOP_PADDING_DP),
            dp(CARD_HORIZONTAL_PADDING_DP),
            dp(CARD_BOTTOM_PADDING_DP),
        )
    }

    private val headline = textView(
        color = PRIMARY_TEXT_COLOR,
        sizeSp = 21f,
        style = Typeface.BOLD,
    ).apply {
        maxLines = 2
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private val reading = textView(
        color = SECONDARY_TEXT_COLOR,
        sizeSp = 13f,
    ).apply {
        maxLines = 2
    }

    private val closeButton = textView(
        color = SECONDARY_TEXT_COLOR,
        sizeSp = 22f,
    ).apply {
        text = "\u00d7"
        contentDescription = context.getString(R.string.subtitle_lookup_close)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { requestDismiss() }
    }

    private val headerAudioButton = audioButton().apply { visibility = View.GONE }

    private val headerMiningButton = miningButton().apply {
        visibility = View.GONE
    }

    private val resultsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val bodyScroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            resultsContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    init {
        visibility = View.GONE
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        ViewCompat.setAccessibilityPaneTitle(
            card,
            context.getString(R.string.subtitle_lookup_popup_pane),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val headingStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                headline,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                reading,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        header.addView(
            headingStack,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            headerAudioButton,
            LinearLayout.LayoutParams(dp(ACTION_TARGET_SIZE_DP), dp(ACTION_TARGET_SIZE_DP))
        )
        header.addView(
            headerMiningButton,
            LinearLayout.LayoutParams(dp(ACTION_TARGET_SIZE_DP), dp(ACTION_TARGET_SIZE_DP)),
        )
        header.addView(
            closeButton,
            LinearLayout.LayoutParams(dp(ACTION_TARGET_SIZE_DP), dp(ACTION_TARGET_SIZE_DP)),
        )
        card.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            bodyScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            },
        )
        addView(
            card,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
    }

    /** Show ranked dictionary entries, keeping long result sets inside the card's scroll area. */
    fun showResults(anchorBounds: RectF, entries: List<DictionaryEntry>) {
        if (entries.isEmpty()) {
            showEmpty(anchorBounds)
            return
        }

        val first = entries.first()
        prepareForShow(
            anchorBounds = anchorBounds,
            title = first.term,
            readingText = first.reading.takeUnless { it.isNullOrBlank() || it == first.term },
        )
        bindMiningButton(headerMiningButton, first)
        bindAudioButton(headerAudioButton, first)
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                resultsContainer.addView(separator())
                resultsContainer.addView(entryHeading(entry))
            }
            resultsContainer.addView(entryDetails(entry))
            resultsContainer.addView(
                bodyText(entry.definition),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = if (index == 0) 0 else dp(4)
                    bottomMargin = dp(RESULT_VERTICAL_PADDING_DP)
                },
            )
        }
        finishShow()
    }

    /** Show a concise no-match state without covering the paused subtitle. */
    fun showEmpty(anchorBounds: RectF) {
        prepareForShow(
            anchorBounds,
            title = context.getString(R.string.subtitle_lookup_popup_empty_title),
        )
        addStateMessage(context.getString(R.string.subtitle_lookup_popup_empty_message))
        finishShow()
    }

    /** Show a generic lookup failure without exposing database details over the video. */
    fun showError(anchorBounds: RectF) {
        prepareForShow(
            anchorBounds,
            title = context.getString(R.string.subtitle_lookup_popup_error_title),
        )
        addStateMessage(context.getString(R.string.subtitle_lookup_popup_error_message))
        finishShow()
    }

    /** Hide the popup and forget its prior anchor and scroll position. */
    fun hide() {
        animate().cancel()
        alpha = 1f
        visibility = View.GONE
        anchorBounds = null
        bodyScroll.scrollTo(0, 0)
        resetMiningControls()
    }

    /** Update the action beside a currently displayed entry; stale or missing entries are ignored. */
    fun setMiningState(entry: DictionaryEntry, state: SubtitleMiningState) {
        val button = miningButtons[entry] ?: return
        actionStates[entry] = state
        applyMiningState(button, entry, state)
        if (state == SubtitleMiningState.ADDED || state == SubtitleMiningState.DUPLICATE) {
            completedEntries[entry] = true
            setDuplicateStatus(entry, AnkiDuplicateStatus.EXISTS)
        }
    }

    fun setDuplicateStatus(entry: DictionaryEntry, status: AnkiDuplicateStatus) {
        // A preflight query begun before insertion must not overwrite the completed action.
        if (completedEntries[entry] == true && status != AnkiDuplicateStatus.EXISTS) return
        miningButtons[entry]?.let { button ->
            // Preserve an in-flight add and its retry state; the indicator is otherwise icon-only.
            if (actionStates[entry] != SubtitleMiningState.IDLE && completedEntries[entry] != true) return
            showBookIcon(button, SubtitleMiningState.IDLE.actionPresentation(status).showBook)
            button.contentDescription = context.getString(
                if (status == AnkiDuplicateStatus.EXISTS) {
                    R.string.subtitle_lookup_mine_duplicate
                } else {
                    R.string.subtitle_lookup_mine_add
                },
                entry.term
            )
        }
    }

    private fun showBookIcon(button: AppCompatTextView, exists: Boolean) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        button.setPadding(if (exists) dp(12) else 0, 0, if (exists) dp(12) else 0, 0)
        button.text = if (exists) "" else "+"
        if (exists) {
            val icon = AppCompatResources.getDrawable(context, R.drawable.ic_learning_book)?.mutate()
            icon?.setTint(palette.accent)
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        }
    }

    fun setAudioLoading(entry: DictionaryEntry, loading: Boolean) {
        audioButtons[entry]?.apply {
            text = if (loading) "\u2026" else "\u25b6"
            isEnabled = !loading
        }
    }

    /** Recalculate placement after the viewport, card contents, or subtitle anchor moves. */
    fun reposition() {
        if (visibility != View.VISIBLE || anchorBounds == null) return
        if (width <= 0 || height <= 0) {
            requestLayout()
            return
        }
        if (card.measuredWidth <= 0 || card.measuredHeight <= 0) {
            requestLayout()
            return
        }
        positionMeasuredCard()
    }

    /** Replace the anchor after the subtitle itself is remeasured or moved. */
    fun updateAnchor(anchorBounds: RectF) {
        if (visibility != View.VISIBLE) return
        this.anchorBounds = RectF(anchorBounds)
        post(::reposition)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val viewportMargins = dp(VIEWPORT_MARGIN_DP) * 2
        card.maximumWidthPx = min(
            dp(CARD_MAX_WIDTH_DP),
            (availableWidth - viewportMargins).coerceAtLeast(0),
        )
        card.minimumWidth = min(dp(CARD_MIN_WIDTH_DP), card.maximumWidthPx)
        card.maximumHeightPx = min(
            (availableHeight * CARD_MAX_HEIGHT_FRACTION).roundToInt(),
            (availableHeight - viewportMargins).coerceAtLeast(0),
        )
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reposition()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) post(::reposition)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            visibility == View.VISIBLE &&
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            !cardContains(event.x, event.y)
        ) {
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun prepareForShow(
        anchorBounds: RectF,
        title: String,
        readingText: String? = null,
    ) {
        applyTheme()
        val wasVisible = isVisible
        resetMiningControls()
        this.anchorBounds = RectF(anchorBounds)
        headline.text = title
        reading.text = readingText.orEmpty()
        reading.visibility = if (readingText.isNullOrBlank()) View.GONE else View.VISIBLE
        resultsContainer.removeAllViews()
        bodyScroll.scrollTo(0, 0)
        visibility = View.VISIBLE
        if (!wasVisible) {
            alpha = 0f
            animate().alpha(1f).setDuration(APPEAR_DURATION_MS).start()
        }
    }

    private fun finishShow() {
        card.requestLayout()
        requestLayout()
        post(::reposition)
    }

    private fun addStateMessage(message: String) {
        resultsContainer.addView(
            bodyText(message),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            },
        )
    }

    private fun entryHeading(entry: DictionaryEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(RESULT_VERTICAL_PADDING_DP), 0, 0)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    textView(
                        color = PRIMARY_TEXT_COLOR,
                        sizeSp = 17f,
                        style = Typeface.BOLD,
                    ).apply {
                        text = entry.term
                    },
                )
                entry.reading
                    ?.takeUnless { it.isBlank() || it == entry.term }
                    ?.let { entryReading ->
                        addView(
                            textView(color = SECONDARY_TEXT_COLOR, sizeSp = 12f).apply {
                                text = entryReading
                            },
                        )
                    }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            audioButton().also { bindAudioButton(it, entry) },
            LinearLayout.LayoutParams(dp(ACTION_TARGET_SIZE_DP), dp(ACTION_TARGET_SIZE_DP))
        )
        addView(
            miningButton().also { button -> bindMiningButton(button, entry) },
            LinearLayout.LayoutParams(dp(ACTION_TARGET_SIZE_DP), dp(ACTION_TARGET_SIZE_DP)),
        )
    }

    private fun miningButton(): AppCompatTextView = textView(
        color = SECONDARY_TEXT_COLOR,
        sizeSp = 22f,
    ).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { button ->
            miningEntries[button]?.let { entry -> onMineRequested?.invoke(entry) }
        }
    }

    private fun entryDetails(entry: DictionaryEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val frequency = entry.frequencies.joinToString(" · ") { "${it.dictionaryTitle}: ${it.displayValue}" }
            listOfNotNull(entry.dictionaryTitle, frequency.takeIf(String::isNotBlank)).forEach { label ->
                addView(textView(SECONDARY_TEXT_COLOR, 11f).apply { text = label })
            }
        }
        addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun audioButton() = textView(SECONDARY_TEXT_COLOR, 18f).apply {
        text = "\u25b6"
        gravity = Gravity.CENTER
        isFocusable = true
    }

    private fun bindAudioButton(button: AppCompatTextView, entry: DictionaryEntry) {
        audioButtons[entry] = button
        button.text = "\u25b6"
        button.isEnabled = true
        button.isVisible = true
        button.contentDescription = context.getString(R.string.subtitle_word_audio_play, entry.term)
        button.setOnClickListener { onAudioRequested?.invoke(entry) }
    }

    private fun bindMiningButton(button: AppCompatTextView, entry: DictionaryEntry) {
        miningButtons[entry] = button
        miningEntries[button] = entry
        button.visibility = View.VISIBLE
        actionStates[entry] = SubtitleMiningState.IDLE
        applyMiningState(button, entry, SubtitleMiningState.IDLE)
    }

    private fun applyMiningState(
        button: AppCompatTextView,
        entry: DictionaryEntry,
        state: SubtitleMiningState,
    ) {
        val presentation = state.actionPresentation()
        showBookIcon(button, presentation.showBook)
        if (!presentation.showBook) button.text = presentation.glyph
        button.contentDescription = context.getString(
            when (state) {
                SubtitleMiningState.IDLE -> R.string.subtitle_lookup_mine_add
                SubtitleMiningState.ADDING -> R.string.subtitle_lookup_mine_adding
                SubtitleMiningState.ADDED -> R.string.subtitle_lookup_mine_added
                SubtitleMiningState.DUPLICATE -> R.string.subtitle_lookup_mine_duplicate
                SubtitleMiningState.ERROR -> R.string.subtitle_lookup_mine_error
            },
            entry.term,
        )
        button.isEnabled = presentation.isEnabled
    }

    private fun resetMiningControls() {
        miningButtons.clear()
        miningEntries.clear()
        audioButtons.clear()
        completedEntries.clear()
        actionStates.clear()
        headerAudioButton.visibility = View.GONE
        headerMiningButton.visibility = View.GONE
        headerMiningButton.text = "+"
        headerMiningButton.contentDescription = null
        headerMiningButton.isEnabled = true
    }

    private fun separator(): View = View(context).apply {
        tag = "separator"
        setBackgroundColor(SEPARATOR_COLOR)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            max(1, dp(1)),
        )
    }

    private fun bodyText(value: String): AppCompatTextView = textView(
        color = PRIMARY_TEXT_COLOR,
        sizeSp = 14f,
    ).apply {
        text = value
        setLineSpacing(0f, 1.12f)
    }

    private fun textView(
        color: Int,
        sizeSp: Float,
        style: Int = Typeface.NORMAL,
    ): AppCompatTextView = AppCompatTextView(context).apply {
        tag = if (color == PRIMARY_TEXT_COLOR) "primary" else "secondary"
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(typeface, style)
        includeFontPadding = false
    }

    private fun positionMeasuredCard() {
        val anchor = anchorBounds ?: return
        val normalizedAnchor = PopupAnchorBounds(
            left = floor(min(anchor.left, anchor.right).toDouble()).toInt(),
            top = floor(min(anchor.top, anchor.bottom).toDouble()).toInt(),
            right = ceil(max(anchor.left, anchor.right).toDouble()).toInt(),
            bottom = ceil(max(anchor.top, anchor.bottom).toDouble()).toInt(),
        )
        val placement = SubtitlePopupPositioner.place(
            anchorBounds = normalizedAnchor,
            popupSize = PopupSize(card.measuredWidth, card.measuredHeight),
            viewportSize = PopupSize(width, height),
            viewportMarginPx = dp(VIEWPORT_MARGIN_DP),
            anchorGapPx = dp(ANCHOR_GAP_DP),
            horizontalLeadPx = dp(HORIZONTAL_LEAD_DP),
        )
        card.x = placement.x.toFloat()
        card.y = placement.y.toFloat()
    }

    private fun cardContains(x: Float, y: Float): Boolean =
        x >= card.x && x < card.x + card.width && y >= card.y && y < card.y + card.height

    private fun requestDismiss() {
        val listener = onDismissRequested
        if (listener == null) hide() else listener()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class BoundedLinearLayout(context: Context) : LinearLayout(context) {
        var maximumWidthPx: Int = Int.MAX_VALUE
        var maximumHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                capMeasureSpec(widthMeasureSpec, maximumWidthPx),
                capMeasureSpec(heightMeasureSpec, maximumHeightPx),
            )
        }

        private fun capMeasureSpec(spec: Int, maximum: Int): Int {
            val mode = MeasureSpec.getMode(spec)
            val size = MeasureSpec.getSize(spec)
            return when (mode) {
                MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST)
                else -> MeasureSpec.makeMeasureSpec(min(size, maximum), mode)
            }
        }
    }
}
