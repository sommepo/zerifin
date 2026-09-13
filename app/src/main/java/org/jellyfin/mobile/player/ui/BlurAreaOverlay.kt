package org.jellyfin.mobile.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.settings.BlurArea
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A movable video-only obscuring area.  Its content is supplied by [setBlurBitmap], so this view
 * never attempts to capture itself or any player controls.
 */
class BlurAreaOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private enum class DragTarget { MOVE, RESIZE }

    private val density = resources.displayMetrics.density
    private val minimumWidth = 80f * density
    private val minimumHeight = 48f * density
    private val handleRadius = 14f * density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 213, 151)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 213, 151) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        setShadowLayer(2f * density, 0f, density, Color.BLACK)
    }

    private var videoViewport: View? = null
    private var area = BlurArea(0.1f, 0.72f, 0.8f, 0.16f)
    private var blurBitmap: Bitmap? = null
    private var dragTarget: DragTarget? = null
    private var touchX = 0f
    private var touchY = 0f
    private var startingBounds = RectF()

    var isEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onAreaChanged: ((BlurArea) -> Unit)? = null
    var onEditFinished: ((BlurArea) -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setVideoViewport(viewport: View?) {
        videoViewport = viewport
        invalidate()
    }

    fun setArea(value: BlurArea?) {
        area = value ?: BlurArea(0.1f, 0.72f, 0.8f, 0.16f)
        invalidate()
    }

    fun setBlurBitmap(bitmap: Bitmap?) {
        blurBitmap?.takeUnless { it === bitmap }?.recycle()
        blurBitmap = bitmap
        invalidate()
    }

    /** Screen-space source rectangle for PixelCopy, or null until both views are laid out. */
    fun areaOnScreen(): Rect? {
        val bounds = areaBounds() ?: return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(
            (location[0] + bounds.left).toInt(),
            (location[1] + bounds.top).toInt(),
            (location[0] + bounds.right).toInt(),
            (location[1] + bounds.bottom).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = areaBounds() ?: return
        val bitmap = blurBitmap
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
        } else {
            canvas.drawRect(bounds, maskPaint)
        }
        if (isEditMode) drawEditor(canvas, bounds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return false
        val bounds = areaBounds() ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTarget = when {
                    distanceToHandle(event.x, event.y, bounds) <= handleRadius * 2 -> DragTarget.RESIZE
                    bounds.contains(event.x, event.y) -> DragTarget.MOVE
                    else -> null
                }
                if (dragTarget != null) {
                    touchX = event.x
                    touchY = event.y
                    startingBounds = RectF(bounds)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTarget == null) return false
                updateArea(bounds, event.x - touchX, event.y - touchY)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val changed = dragTarget != null
                dragTarget = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (changed) onEditFinished?.invoke(area)
                changed
            }
            else -> dragTarget != null
        }
    }

    override fun onDetachedFromWindow() {
        blurBitmap?.recycle()
        blurBitmap = null
        super.onDetachedFromWindow()
    }

    private fun drawEditor(canvas: Canvas, bounds: RectF) {
        canvas.drawRect(bounds, borderPaint)
        canvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handlePaint)
        val savedCanvas = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawText(
            context.getString(R.string.learning_blur_area_edit_hint),
            bounds.centerX(),
            bounds.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2,
            hintPaint,
        )
        canvas.restoreToCount(savedCanvas)
    }

    private fun updateArea(currentBounds: RectF, deltaX: Float, deltaY: Float) {
        val viewport = viewportBounds() ?: return
        val next = when (dragTarget) {
            DragTarget.MOVE -> RectF(startingBounds).apply {
                offset(deltaX, deltaY)
                offset(
                    when {
                        left < viewport.left -> viewport.left - left
                        right > viewport.right -> viewport.right - right
                        else -> 0f
                    },
                    when {
                        top < viewport.top -> viewport.top - top
                        bottom > viewport.bottom -> viewport.bottom - bottom
                        else -> 0f
                    },
                )
            }
            DragTarget.RESIZE -> RectF(startingBounds).apply {
                right = (right + deltaX).coerceIn(left + minimumWidth, viewport.right)
                bottom = (bottom + deltaY).coerceIn(top + minimumHeight, viewport.bottom)
            }
            null -> currentBounds
        }
        area = BlurArea(
            x = (next.left - viewport.left) / viewport.width(),
            y = (next.top - viewport.top) / viewport.height(),
            width = next.width() / viewport.width(),
            height = next.height() / viewport.height(),
        )
        onAreaChanged?.invoke(area)
        invalidate()
    }

    private fun areaBounds(): RectF? {
        val viewport = viewportBounds() ?: return null
        val width = min(maximumWidth(viewport), max(minimumWidth, viewport.width() * area.width))
        val height = min(maximumHeight(viewport), max(minimumHeight, viewport.height() * area.height))
        val left = (viewport.left + viewport.width() * area.x).coerceIn(viewport.left, viewport.right - width)
        val top = (viewport.top + viewport.height() * area.y).coerceIn(viewport.top, viewport.bottom - height)
        return RectF(left, top, left + width, top + height)
    }

    private fun maximumWidth(viewport: RectF) = max(minimumWidth, viewport.width())
    private fun maximumHeight(viewport: RectF) = max(minimumHeight, viewport.height())

    private fun viewportBounds(): RectF? {
        val viewport = videoViewport ?: return null
        if (!isVisible || width == 0 || height == 0 || viewport.width == 0 || viewport.height == 0) return null
        val overlayLocation = IntArray(2)
        val viewportLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        viewport.getLocationOnScreen(viewportLocation)
        val left = (viewportLocation[0] - overlayLocation[0]).toFloat()
        val top = (viewportLocation[1] - overlayLocation[1]).toFloat()
        val viewportBounds = RectF(left, top, left + viewport.width, top + viewport.height)
        if (!viewportBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return null
        return viewportBounds.takeIf { it.width() > minimumWidth && it.height() > minimumHeight }
    }

    private fun distanceToHandle(x: Float, y: Float, bounds: RectF): Float =
        max(abs(x - bounds.right), abs(y - bounds.bottom))
}
