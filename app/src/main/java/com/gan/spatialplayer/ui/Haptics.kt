package com.gan.spatialplayer.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's haptic vocabulary.
 *
 * Feedback is deliberately not uniform: a light tick acknowledges that a control registered,
 * while a firmer one confirms that something actually changed. Using the same strength everywhere
 * makes the phone feel like it is buzzing at random rather than responding.
 */
object Haptics {

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
        view.performHapticFeedback(
            constant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
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
        view.performHapticFeedback(
            constant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }
}
