package org.jellyfin.mobile.player.ui

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Recognises a quick tap with two fingers that neither moves nor pinches. It only observes the
 * stream, so pinch-zoom and the other player gestures keep working.
 */
class TwoFingerTapDetector(private val touchSlop: Float, private val tapTimeoutMs: Long) {
    private var tracking = false
    private var secondDownTime = 0L
    private val startX = FloatArray(2)
    private val startY = FloatArray(2)

    /** Returns true once, on the lift that completes a two-finger tap. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tracking = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = event.pointerCount == 2 && event.eventTime - event.downTime <= tapTimeoutMs
                if (tracking) {
                    secondDownTime = event.eventTime
                    for (index in 0..1) {
                        startX[index] = event.getX(index)
                        startY[index] = event.getY(index)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (tracking && moved(event)) tracking = false
            MotionEvent.ACTION_POINTER_UP -> {
                val wasTap = tracking && event.pointerCount == 2 && !moved(event) &&
                    event.eventTime - secondDownTime <= tapTimeoutMs
                tracking = false
                return wasTap
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    private fun moved(event: MotionEvent): Boolean {
        if (event.pointerCount < 2) return false
        return (0..1).any { index ->
            abs(event.getX(index) - startX[index]) > touchSlop || abs(event.getY(index) - startY[index]) > touchSlop
        } || event.pointerCount > 2
    }
}
