package org.spatialplayer.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Gesture policy for the player surface, kept separate from the activity so the interaction rules
 * are readable in one place.
 *
 * Mapping (deliberately the conventions people already have in their hands):
 *
 *  * single tap - show or hide the controls
 *  * double tap - ask the host to seek, direction chosen by which half was tapped
 *  * horizontal drag - scrub at a fixed time per dp, independent of media duration
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

        /** Signed time offset in milliseconds relative to where the drag began. */
        fun onScrubMove(deltaMs: Long)

        /** Final signed time offset to commit as a single seek. */
        fun onScrubStop(deltaMs: Long)
        fun onScrubCancel()

        fun onZoom(scale: Float)
        fun onBrightnessDelta(delta: Float)
        fun onVolumeDelta(delta: Float)
    }


    private var downX = 0f
    private var lastX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var mode = Mode.NONE
    private var lastY = 0f
    private var lastZoomSpan = 0f
    private var accumulatedMs = 0.0

    private enum class Mode { NONE, UNDECIDED, SEEK, BRIGHTNESS, VOLUME, ZOOM }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Pending single-tap, held only long enough to see whether a second tap follows.
     *
     * `GestureDetector.onSingleTapConfirmed` would be the obvious hook, but it waits out the whole
     * double-tap window before firing, so the first tap always felt dead. Handling the tap here and
     * deciding afterwards means the chrome appears on the first touch while a double tap still
     * cancels the pending single-tap action.
     */
    private var pendingSingleTap: Runnable? = null

    /**
     * Half-screen split for double-tap seeking.
     *
     * Previously the threshold sat at 35% of the width, which meant a tap at the far right of the
     * left-hand side already counted as "forward". The screen midline is what people expect.
     */
    private val doubleTapSplitFraction = 0.5f

    /** Jump length for a double tap, in ms. Owned by the activity so Settings can change it. */
    var doubleTapJumpMs: Long = 10_000L

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Defer just past the double-tap window; a second tap cancels this.
                cancelPendingSingleTap()
                val runnable = Runnable {
                    pendingSingleTap = null
                    host.onSingleTap()
                }
                pendingSingleTap = runnable
                handler.postDelayed(runnable, DOUBLE_TAP_TIMEOUT_MS)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelPendingSingleTap()
                val forward = e.x > viewWidth * doubleTapSplitFraction
                host.onDoubleTap(forward)
                return true
            }
        },
    )

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    /** Stops the deferred single-tap from firing after the host goes away. */
    fun release() {
        cancelPendingSingleTap()
    }

    private var viewWidth = 1
    private var viewHeight = 1

    /**
     * Height that vertical drags are measured against, which is deliberately **not** [viewHeight].
     *
     * [viewHeight] is the picture's rectangle, because the left/right halves and the scrub area are
     * the picture's. Scaling the drag by it too made the sensitivity depend on the video's aspect
     * ratio: the same finger movement covered four times as much of the volume range over a 16:9
     * letterboxed picture (616 px tall in portrait) as it would over a taller one, and the shorter
     * the picture, the twitchier the gesture. Measured: a one-centimetre drag moved 7 of 33 volume
     * steps, with the same 0.30 gain that moved one step when the viewport happened to be taller.
     * Setting this to the window height makes the gesture feel identical no matter what is playing.
     */
    private var dragReferenceHeight = 1

    /** True while a vertical gesture is waiting out [ADJUST_HOLD_MS] before it may act. */
    private var adjustArmed = false
    private var slopCrossedAtMs = 0L

    /**
     * Fraction of the range one screen-height vertical drag covers.
     *
     * Exposed so Settings can offer a sensitivity control rather than hard-coding a value that may
     * not suit every hand. Defaults low: the previous 0.6 meant a two-centimetre nudge moved the
     * volume several steps, which is what "dragging less than a centimetre sends it through the
     * roof" described.
     */
    var verticalGain: Float = 0.30f
        set(value) {
            field = value.coerceIn(0.05f, 1.5f)
        }

    /** Caps a single event's contribution so a coalesced or jumpy MOVE cannot spike the value. */
    private fun clampStep(delta: Float): Float =
        delta.coerceIn(-MAX_STEP_FRACTION, MAX_STEP_FRACTION)

    fun setViewport(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        if (dragReferenceHeight <= 1) dragReferenceHeight = viewHeight
    }

    /** The window height; see [dragReferenceHeight]. Not the picture's. */
    fun setDragReferenceHeight(height: Int) {
        dragReferenceHeight = height.coerceAtLeast(1)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                accumulatedMs = 0.0
                mode = Mode.UNDECIDED
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if(mode==Mode.SEEK)host.onScrubCancel()
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
                        // Wait a beat before acting. A touch that is still accelerating into a
                        // system gesture (a shade pull, a back swipe) often crosses the slop
                        // threshold on its way out; requiring the finger to still be moving after
                        // this pause filters those out without adding perceptible latency to a
                        // deliberate drag.
                        adjustArmed = mode != Mode.SEEK && mode != Mode.NONE
                        slopCrossedAtMs = System.currentTimeMillis()
                        when (mode) {
                            Mode.SEEK -> host.onScrubStart()
                            Mode.BRIGHTNESS -> host.onBrightnessDelta(0f)
                            Mode.VOLUME -> host.onVolumeDelta(0f)
                            else -> Unit
                        }
                        // Rebase the reference to the current position: the travel that triggered
                        // the gesture must not also be applied as an adjustment, or the first
                        // update jumps by the whole slop distance.
                        lastY = event.y
                        lastX = event.x
                        accumulatedMs = 0.0
                    }
                }

                // The hold delay is measured from the moment the axis was locked.
                if (adjustArmed) {
                    val waited = System.currentTimeMillis() - slopCrossedAtMs
                    if (waited < ADJUST_HOLD_MS) return true
                    adjustArmed = false
                    // The finger may have travelled far during the hold; treat that travel as
                    // approach, not as an adjustment.
                    lastY = event.y
                    lastX = event.x
                }

                when (mode) {
                    Mode.SEEK -> {
                        // Accumulate movement since the gesture began, so triggering the gesture
                        // does not itself seek.
                        accumulatedMs += (event.x-lastX)/context.resources.displayMetrics.density * SEEK_MS_PER_DP
                        lastX = event.x
                        host.onScrubMove(accumulatedMs.toLong())
                    }

                    Mode.BRIGHTNESS -> {
                        val delta = clampStep(
                            (lastY - event.y) / dragReferenceHeight * verticalGain,
                        )
                        lastY = event.y
                        if (delta != 0f) host.onBrightnessDelta(delta)
                    }

                    Mode.VOLUME -> {
                        val delta = clampStep(
                            (lastY - event.y) / dragReferenceHeight * verticalGain,
                        )
                        lastY = event.y
                        if (delta != 0f) host.onVolumeDelta(delta)
                    }

                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SEEK) {
                    if(event.actionMasked==MotionEvent.ACTION_CANCEL)host.onScrubCancel()
                    else host.onScrubStop(accumulatedMs.toLong())
                }
                mode = Mode.NONE
                adjustArmed = false
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
        const val SEEK_MS_PER_DP = 100.0 // 100dp = 10 seconds in every file.
        /**
         * Touch travel, in dp, before a drag is interpreted as anything.
         *
         * Was 14dp, which is below the distance at which a system edge swipe is recognised, so the
         * player often claimed touches the user meant for the system.
         */
        const val SLOP_DP = 48f

        /** Matches the platform's typical back-gesture edge band. */
        const val SIDE_GESTURE_INSET_DP = 30f

        /** Top region where a downward swipe belongs to the notification shade. */
        const val TOP_SYSTEM_INSET_DP = 60f

        /**
         * How long a single tap waits to find out whether it is really a double tap.
         *
         * Matches the platform's own double-tap window; longer would make the chrome feel laggy,
         * shorter would turn deliberate double taps into two separate taps.
         */
        const val DOUBLE_TAP_TIMEOUT_MS = 260L

        /**
         * Pause after the axis locks before a vertical gesture may change anything.
         *
         * Long enough to let a touch that is really heading for the notification shade or the back
         * gesture declare itself, short enough that a deliberate drag feels immediate.
         */
        const val ADJUST_HOLD_MS = 60L

        /**
         * Largest share of the range a single `ACTION_MOVE` may apply.
         *
         * Android coalesces batched move events into one callback during fast drags, so a single
         * delta can arrive far larger than the distance between two frames; without a cap that
         * becomes a jump.
         */
        const val MAX_STEP_FRACTION = 0.03f

        /** Default vertical sensitivity; see [verticalGain]. */
        const val DEFAULT_VERTICAL_GAIN = 0.30f

    }
}
