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
    private var lastX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var mode = Mode.NONE
    private var lastY = 0f
    private var lastZoomSpan = 0f
    private var accumulatedFraction = 0f

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
    private var doubleTapSplitFraction = 0.5f

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

    /** Sets the midline split, clamped so a usable band remains on each side. */
    fun setDoubleTapSplitFraction(fraction: Float) {
        doubleTapSplitFraction = fraction.coerceIn(0.2f, 0.8f)
    }

    private var viewWidth = 1
    private var viewHeight = 1

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
                lastX = event.x
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
                        // Wait a beat before acting. A touch that is still accelerating into a
                        // system gesture (a shade pull, a back swipe) often crosses the slop
                        // threshold on its way out; requiring the finger to still be moving after
                        // this pause filters those out without adding perceptible latency to a
                        // deliberate drag.
                        adjustArmed = mode != Mode.SEEK && mode != Mode.NONE
                        slopCrossedAtMs = System.currentTimeMillis()
                        if (mode == Mode.SEEK) {
                            host.onScrubStart()
                        }
                        // Rebase the reference to the current position: the travel that triggered
                        // the gesture must not also be applied as an adjustment, or the first
                        // update jumps by the whole slop distance.
                        lastY = event.y
                        lastX = event.x
                        accumulatedFraction = 0f
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
                        accumulatedFraction += (event.x - lastX) / viewWidth
                        lastX = event.x
                        host.onScrubMove(accumulatedFraction)
                    }

                    Mode.BRIGHTNESS -> {
                        val delta = clampStep(
                            (lastY - event.y) / viewHeight * verticalGain,
                        )
                        lastY = event.y
                        if (delta != 0f) host.onBrightnessDelta(delta)
                    }

                    Mode.VOLUME -> {
                        val delta = clampStep(
                            (lastY - event.y) / viewHeight * verticalGain,
                        )
                        lastY = event.y
                        if (delta != 0f) host.onVolumeDelta(delta)
                    }

                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SEEK) {
                    host.onScrubStop(accumulatedFraction)
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

/**
 * Small animation helpers used by the player chrome.
 *
 * ## The bug this design exists to prevent
 *
 * The first version kept the chrome's `visibility` as the hidden state: `hide` faded alpha to 0 and
 * set `View.GONE` in its end listener, and `show` cancelled that animation and set
 * `View.VISIBLE` again. It looked correct and was not.
 *
 * `View.animate()` returns one shared `ViewPropertyAnimator` per view, and **`cancel()` does not
 * clear its listener**. The listener installed by `hide` therefore stayed attached, and when the
 * *next* animation (the one started by `show`) finished, `onAnimationEnd` ran `visibility = GONE`.
 * The chrome was made visible and then immediately hidden again by a leftover callback, so every
 * second tap appeared to do nothing at all. Measured 200ms after a `show`: `visibility=8` (GONE).
 *
 * ## The rule now
 *
 * Nothing animates `visibility`. The view stays `VISIBLE` for its whole life and *alpha alone*
 * expresses whether the chrome is showing, so no end callback is needed to establish the hidden
 * state and no stale listener can undo a `show`. `stop()` clears the listener before every new
 * animation so a superseded animation cannot fire an action for a state that no longer applies.
 */
object PlayerAnimation {

    /** Alpha below which the chrome counts as hidden. */
    private const val HIDDEN_ALPHA = 0.01f

    /** True when the chrome is genuinely on screen. */
    fun isShown(view: View): Boolean = view.visibility == View.VISIBLE && view.alpha > HIDDEN_ALPHA

    /**
     * Cancels any running animation and detaches its listeners.
     *
     * `cancel()` alone is not enough - see the note above - hence the explicit `setListener(null)`.
     */
    private fun stop(view: View) {
        view.animate().cancel()
        view.animate().setListener(null)
        view.animate().setUpdateListener(null)
    }

    /**
     * Reveals the chrome.
     *
     * Alpha is assigned up front rather than animated from its previous value: a fade that begins
     * from an alpha left behind by a previous [hide] is what made the controls invisible even though
     * the code had just asked for them to be shown.
     */
    fun show(view: View, durationMs: Long = 160L) {
        stop(view)
        val wasShown = isShown(view)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        if (wasShown) return

        // A small scale settle reads as "arrived" without delaying legibility.
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Fades the chrome out. Purely alpha; visibility is never touched. */
    fun hide(view: View, durationMs: Long = 140L, endAction: (() -> Unit)? = null) {
        stop(view)
        view.visibility = View.VISIBLE
        if (!isShown(view)) {
            view.alpha = 0f
            endAction?.invoke()
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { endAction?.invoke() }
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
