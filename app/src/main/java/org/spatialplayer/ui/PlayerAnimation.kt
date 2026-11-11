package org.spatialplayer.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

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
 * Alpha alone expresses whether the chrome is showing, and `stop()` clears the listener before every
 * new animation so a superseded animation cannot fire an action for a state that no longer applies.
 * No stale callback can undo a `show`.
 *
 * The one exception is [hide]'s `gone` flag, which parks the faded view at `INVISIBLE` so it stops
 * taking touches - alpha does not affect hit-testing, and an invisible-but-clickable chrome was the
 * second half of the same bug. It is safe from the stale-listener trap because `stop()` runs first on
 * every path, and because `show` always resets the view to `VISIBLE` before looking at its alpha, so
 * a parked view is un-parked by definition rather than by a callback arriving in the right order.
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

    /**
     * Fades the chrome out.
     *
     * Alpha is still the state; visibility is never touched *by the animation*. When [gone] is set,
     * however, the view is made `INVISIBLE` once the fade has finished, and that is the whole reason
     * the flag exists: a transparent view still receives touches, so a chrome that had faded to
     * `alpha = 0` kept swallowing taps on Back, Inspect and Metadata - the buttons were invisible but
     * still clicked, and the gesture layer never saw the touch that should have brought the chrome
     * back. `INVISIBLE` rather than `GONE` because the view's measured size is what the gesture
     * controller's viewport and the capsule's placement are computed from.
     *
     * It is opt-in because this object is also used for transient overlays - the seek feedback and
     * the error card - which are deliberately left `VISIBLE` at zero alpha so they keep their place
     * in the layout.
     */
    fun hide(
        view: View,
        durationMs: Long = 140L,
        endAction: (() -> Unit)? = null,
        gone: Boolean = false,
    ) {
        stop(view)
        view.visibility = View.VISIBLE
        val settle: () -> Unit = {
            if (gone) view.visibility = View.INVISIBLE
            endAction?.invoke()
        }
        if (!isShown(view)) {
            view.alpha = 0f
            settle()
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(settle)
            .start()
    }

    private val dismissals = java.util.WeakHashMap<View, Runnable>()

    /** Repeated gesture updates replace the text without restarting its entrance animation. */
    fun pulse(view: View, holdMs: Long = 620L) {
        val visible = dismissals.containsKey(view) || isShown(view)
        dismissals.remove(view)?.let { view.removeCallbacks(it) }
        stop(view)
        view.animate().setStartDelay(0L)
        view.visibility = View.VISIBLE
        if (visible) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        } else {
            view.alpha = 1f
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0L)
                .setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
        }
        val dismiss = Runnable {
            dismissals.remove(view)
            hide(view, durationMs = 180L, gone = true)
        }
        dismissals[view] = dismiss
        view.postDelayed(dismiss, holdMs)
    }
}
