@file:Suppress("DEPRECATION")

package com.gan.spatialplayer.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The app's haptic vocabulary.
 *
 * Feedback is deliberately not uniform: a light tick acknowledges that a control registered, while
 * a firmer one confirms that something actually changed. Using one strength everywhere makes the
 * phone feel like it is buzzing at random rather than responding.
 *
 * Two mechanisms are used, in this order:
 *
 *  1. **`View.performHapticFeedback`** for the standard vocabulary. It is cheap, respects the
 *     system's own tuning, and works without any permission.
 *  2. **`Vibrator` compositions with explicit amplitudes** for the effects the platform constants
 *     cannot express (a double pulse for a snap, a soft thud for a panel closing). The reference
 *     device reports `AMPLITUDE_CONTROL`, so these actually vary in strength rather than falling
 *     back to a single buzz.
 *
 * Everything degrades quietly: a device without amplitude control, or with haptics disabled by the
 * user, simply gets nothing rather than a crash.
 */
object Haptics {

    // ---------------------------------------------------------------- platform vocabulary

    /** A control was touched. Barely there; just enough to feel connected. */
    fun touch(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }

    /** A control was released and its action committed. Noticeably firmer than [touch]. */
    fun release(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    /**
     * A finger lifted off a control.
     *
     * Deliberately the lightest thing in this file, and lighter than [touch]. The press says "your
     * finger has landed on something", the lift says "and now it has left"; making the two the same
     * strength reads as two separate taps rather than as one press.
     */
    fun lift(view: View) {
        compose(view, singlePulse(amplitude = 45, durationMs = 7L))
            ?: view.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    /**
     * Gives a control the press/lift pair in one place, so every control feels the same.
     *
     * The listener never consumes the event: returning false leaves the view's own click handling,
     * ripples and long-press alone. `ACTION_CANCEL` is ignored on purpose - it means a parent
     * (a scrolling list, say) took the gesture over, and buzzing on the way out of a scroll would be
     * noise rather than feedback.
     */
    fun attachTo(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touch(touched)
                MotionEvent.ACTION_UP -> lift(touched)
                else -> Unit
            }
            false
        }
    }

    /** A discrete value was crossed during a continuous drag. */
    fun tick(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }

    /** Something went wrong or was refused. */
    fun reject(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    // ---------------------------------------------------------------- compositions

    /**
     * Two quick pulses: a value snapped into place.
     *
     * Used where a single tick would be lost — a seek settling, a tab or track being chosen — so
     * the confirmation is distinguishable from ordinary drag feedback by feel alone.
     */
    fun snap(view: View) {
        compose(view, doublePulse(amplitude = 150, gapMs = 28L))
            ?: view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    /** A panel opened. Soft and short, so it reads as "arrived" rather than as an alert. */
    fun open(view: View) {
        compose(view, doublePulse(amplitude = 90, gapMs = 18L))
            ?: view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    /** A panel closed. Heavier than [open] so the two are not confused. */
    fun close(view: View) {
        compose(view, singlePulse(amplitude = 120, durationMs = 18L))
            ?: view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    /** A heavier confirmation for a completed, discrete action such as a chapter jump. */
    fun bump(view: View) {
        compose(view, singlePulse(amplitude = 200, durationMs = 22L))
            ?: view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    /** An error. A short double pulse that is clearly not a confirmation. */
    fun error(view: View) {
        compose(view, doublePulse(amplitude = 210, gapMs = 90L))
            ?: view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                },
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
    }

    // ---------------------------------------------------------------- plumbing

    private fun singlePulse(amplitude: Int, durationMs: Long): VibrationEffect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
        } else {
            null
        }

    private fun doublePulse(amplitude: Int, gapMs: Long): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val amp = amplitude.coerceIn(1, 255)
        return VibrationEffect.createWaveform(
            longArrayOf(0L, 16L, gapMs, 16L),
            intArrayOf(0, amp, 0, amp),
            -1,
        )
    }

    /**
     * Plays [effect] through the platform vibrator, or returns null when unavailable.
     *
     * The view is only used to reach a context and to check that the window is attached; a
     * detached view must not leave a vibration running.
     */
    private fun compose(view: View, effect: VibrationEffect?): Unit? {
        if (effect == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!view.isAttachedToWindow) return null
        val vibrator = vibratorFor(view.context) ?: return null
        if (!vibrator.hasAmplitudeControl()) return null
        return runCatching { vibrator.vibrate(effect) }.getOrNull()
    }

    private fun vibratorFor(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()
}
