package com.gan.spatialplayer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * Gesture policy for the player surface, kept separate from the activity so the interaction rules
 * are readable in one place.
 *
 * Mapping (deliberately the conventions people already have in their hands):
 *
 *  * single tap - show or hide the controls
 *  * double tap - seek by [seekStepMs], direction chosen by which half was tapped
 *  * horizontal drag - scrub; the drag distance maps to the whole duration
 *  * vertical drag on the left half - brightness (left to the activity, which owns the window)
 *  * vertical drag on the right half - volume
 *  * pinch - zoom the picture without changing its aspect ratio
 */
class PlayerGestureController(
    private val context: Context,
    private val host: Host,
) {

    interface Host {
        fun onSingleTap()
        fun onDoubleTap(forward: Boolean)
        fun onScrubStart()

        /** Signed fraction of the total duration relative to where the drag began. */
        fun onScrubMove(deltaFraction: Float)

        /** Final signed fraction to commit as a single seek. */
        fun onScrubStop(deltaFraction: Float)

        fun onZoom(scale: Float)
        fun onBrightnessDelta(delta: Float)
        fun onVolumeDelta(delta: Float)
    }

    /** Distance the finger must travel for a full-height gesture to mean "100%". */
    private var seekStepMs: Long = 10_000L
    private var durationMs: Long = 0L

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var mode = Mode.NONE
    private var lastY = 0f
    private var lastZoomSpan = 0f
    private var accumulatedFraction = 0f

    private enum class Mode { NONE, UNDECIDED, SEEK, BRIGHTNESS, VOLUME, ZOOM }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                host.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Left third rewinds, right two thirds fast-forwards.
                val forward = e.x > viewWidth * 0.35f
                host.onDoubleTap(forward)
                return true
            }
        },
    )

    private var viewWidth = 1
    private var viewHeight = 1

    fun setViewport(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    fun setSeekStep(stepMs: Long) {
        seekStepMs = stepMs
    }

    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                downTime = System.currentTimeMillis()
                accumulatedFraction = 0f
                mode = Mode.UNDECIDED
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mode = Mode.ZOOM
                    lastZoomSpan = span(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ZOOM && event.pointerCount >= 2) {
                    val currentSpan = span(event)
                    if (lastZoomSpan > 0f) {
                        host.onZoom(currentSpan / lastZoomSpan)
                    }
                    lastZoomSpan = currentSpan
                    return true
                }

                if (mode == Mode.UNDECIDED) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    val slop = SLOP_DP * context.resources.displayMetrics.density
                    if (dx > slop || dy > slop) {
                        mode = when {
                            // Horizontal drags scrub, but not when they began inside the band the
                            // system reserves for its own back gesture.
                            dx > dy ->
                                if (startedInSystemGestureEdge(downX)) Mode.NONE else Mode.SEEK

                            // Vertical drags adjust brightness/volume, but not when they began
                            // near the top, where the system reads the same swipe as "pull down
                            // the notification shade".
                            downY < topSystemGestureInset() -> Mode.NONE

                            downX < viewWidth / 2f -> Mode.BRIGHTNESS
                            else -> Mode.VOLUME
                        }
                        if (mode == Mode.SEEK) {
                            host.onScrubStart()
                        }
                        lastY = event.y
                    }
                }

                when (mode) {
                    Mode.SEEK -> {
                        // Full screen width maps to the whole duration.
                        val deltaFraction = (event.x - downX) / viewWidth
                        accumulatedFraction = deltaFraction
                        host.onScrubMove(accumulatedFraction)
                    }

                    Mode.BRIGHTNESS -> {
                        val delta = (lastY - event.y) / viewHeight * VERTICAL_GAIN
                        lastY = event.y
                        host.onBrightnessDelta(delta)
                    }

                    Mode.VOLUME -> {
                        val delta = (lastY - event.y) / viewHeight * VERTICAL_GAIN
                        lastY = event.y
                        host.onVolumeDelta(delta)
                    }

                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SEEK) {
                    host.onScrubStop(accumulatedFraction)
                }
                mode = Mode.NONE
                lastZoomSpan = 0f
            }
        }
        return true
    }

    /**
     * True when [x] is inside the left or right edge band the system uses for the back gesture.
     *
     * Touch events are still delivered to the app during an edge swipe - the system only claims
     * them once it recognises the gesture - so without this a scrub would begin underneath the
     * user's back gesture.
     */
    private fun startedInSystemGestureEdge(x: Float): Boolean {
        val inset = systemGestureInset()
        return x < inset || x > viewWidth - inset
    }

    /** Height of the region reserved at the top for pulling down the notification shade. */
    private fun topSystemGestureInset(): Float =
        context.resources.displayMetrics.density * TOP_SYSTEM_INSET_DP

    private fun systemGestureInset(): Float =
        context.resources.displayMetrics.density * SIDE_GESTURE_INSET_DP

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return kotlin.math.hypot(dx, dy)
    }

    private companion object {
        /**
         * Touch travel, in dp, before a drag is interpreted as anything.
         *
         * Was 14dp, which is below the distance at which a system edge swipe is recognised, so the
         * player often claimed touches the user meant for the system.
         */
        const val SLOP_DP = 30f

        /** Matches the platform's typical back-gesture edge band. */
        const val SIDE_GESTURE_INSET_DP = 30f

        /** Top region where a downward swipe belongs to the notification shade. */
        const val TOP_SYSTEM_INSET_DP = 60f

        /**
         * Fraction of the full range that one screen-height drag covers.
         *
         * At 1.0 a quarter-screen flick changed volume by 25%, which felt twitchy. 0.6 makes a
         * full-height drag cover 60% of the range, so small adjustments are easy to hit.
         */
        const val VERTICAL_GAIN = 0.6f
    }
}

/**
 * Small animation helpers used by the player chrome. Kept here so the activity reads as intent
 * rather than as tween configuration.
 */
object PlayerAnimation {

    fun fadeIn(view: View, durationMs: Long = 200L) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun fadeOut(view: View, durationMs: Long = 180L, endAction: (() -> Unit)? = null) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        view.visibility = View.GONE
                        endAction?.invoke()
                    }
                }
            })
            .start()
    }

    /** Pulse for the transient seek/zoom feedback chips. */
    fun pulse(view: View, holdMs: Long = 620L) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .alpha(0f)
                    .setStartDelay(holdMs)
                    .setDuration(200L)
                    .withEndAction { view.visibility = View.GONE }
                    .start()
            }
            .start()
    }
}
