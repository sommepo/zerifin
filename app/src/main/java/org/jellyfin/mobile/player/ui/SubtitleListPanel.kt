package org.jellyfin.mobile.player.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.mining.MiningCue
import kotlin.math.min

/**
 * A side panel listing every Japanese subtitle line. Tapping a line asks the player to seek to
 * its start; the line under the playhead is highlighted and kept in view.
 */
class SubtitleListPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onLineSelected: ((MiningCue) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val title = TextView(context)
    private val list = ListView(context)
    private val adapter = LineAdapter()
    private var cues: List<MiningCue> = emptyList()
    private var activeIndex = -1
    private var userScrolledAtMs = 0L

    init {
        orientation = VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            setColor(PANEL_COLOR)
            cornerRadius = 12 * density
        }
        val padding = (8 * density).toInt()
        setPadding(padding, padding, padding, padding)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(padding, 0, padding, 0)
            text = context.getString(R.string.learning_subtitle_list_title)
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_close_white_24dp)
                setBackgroundResource(R.drawable.playback_unlock_background)
                contentDescription = context.getString(R.string.learning_subtitle_list_close)
                setOnClickListener { onCloseRequested?.invoke() }
            },
            LayoutParams((40 * density).toInt(), (40 * density).toInt()),
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.apply {
            divider = null
            isVerticalScrollBarEnabled = true
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            adapter = this@SubtitleListPanel.adapter
            setOnItemClickListener { _, _, position, _ -> cues.getOrNull(position)?.let { onLineSelected?.invoke(it) } }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                    if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        userScrolledAtMs = android.os.SystemClock.uptimeMillis()
                    }
                }

                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
            })
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setCues(lines: List<MiningCue>) {
        cues = lines
        activeIndex = -1
        title.text = context.getString(R.string.learning_subtitle_list_count, lines.size)
        adapter.notifyDataSetChanged()
    }

    /** Highlight the line at [positionMs] and scroll to it unless the viewer is browsing. */
    fun updatePosition(positionMs: Long, forceScroll: Boolean = false) {
        val index = SubtitleListIndex.activeLine(cues, positionMs)
        if (index == activeIndex && !forceScroll) return
        activeIndex = index
        adapter.notifyDataSetChanged()
        val browsing = android.os.SystemClock.uptimeMillis() - userScrolledAtMs < BROWSE_HOLD_MS
        if (index >= 0 && (forceScroll || !browsing)) {
            list.setSelectionFromTop(index, list.height / 3)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width = min((parentWidth * WIDTH_FRACTION).toInt(), (MAX_WIDTH_DP * density).toInt())
            .coerceAtLeast(min(parentWidth, (MIN_WIDTH_DP * density).toInt()))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    // The panel sits above the player; touches inside it never reach the video gestures.
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private inner class LineAdapter : BaseAdapter() {
        override fun getCount(): Int = cues.size

        override fun getItem(position: Int): MiningCue = cues[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val horizontal = (10 * density).toInt()
                val vertical = (8 * density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                setLineSpacing(0f, 1.1f)
            }
            val cue = cues[position]
            row.text = context.getString(
                R.string.learning_subtitle_list_line,
                SubtitleListIndex.formatTime(cue.startMs),
                cue.text.replace('\n', ' '),
            )
            val active = position == activeIndex
            row.setTextColor(if (active) Color.WHITE else INACTIVE_TEXT)
            row.background = if (active) {
                GradientDrawable().apply {
                    setColor(ACTIVE_ROW_COLOR)
                    cornerRadius = 8 * density
                }
            } else {
                null
            }
            return row
        }
    }

    private companion object {
        const val WIDTH_FRACTION = 0.45f
        const val MAX_WIDTH_DP = 420
        const val MIN_WIDTH_DP = 260
        const val BROWSE_HOLD_MS = 4_000L
        val PANEL_COLOR = Color.argb(230, 14, 20, 17)
        val ACTIVE_ROW_COLOR = Color.argb(150, 15, 127, 82)
        val INACTIVE_TEXT = Color.rgb(196, 208, 201)
    }
}

/** Pure helpers for the subtitle line list. */
object SubtitleListIndex {
    /** The line playing at [positionMs], or the most recent line before it. */
    fun activeLine(cues: List<MiningCue>, positionMs: Long): Int {
        var result = -1
        for ((index, cue) in cues.withIndex()) {
            if (cue.startMs > positionMs) break
            result = index
        }
        return result
    }

    @Suppress("MagicNumber")
    fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
