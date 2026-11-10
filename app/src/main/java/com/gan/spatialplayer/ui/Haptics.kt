package com.gan.spatialplayer.ui

import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import com.gan.spatialplayer.R
import java.util.WeakHashMap

/** One touch vocabulary for every control. System haptic preferences are respected. */
object Haptics {
    internal var feedbackCount = 0L
        private set
    private val lastFeedback = WeakHashMap<View, Long>()
    private fun feedback(view: View, kind: Int) {
        if (!view.isHapticFeedbackEnabled || !view.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (now - (lastFeedback[view] ?: -100L) < 45L) return
        if (Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        lastFeedback[view] = now
        val played = view.performHapticFeedback(kind) || view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (played) feedbackCount++
    }
    fun touch(view: View) = feedback(view, HapticFeedbackConstants.GESTURE_START)
    fun release(view: View) = feedback(view, HapticFeedbackConstants.CONFIRM)
    fun lift(view: View) = feedback(view, HapticFeedbackConstants.GESTURE_END)
    fun tick(view: View) = feedback(view, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun snap(view: View) = feedback(view, HapticFeedbackConstants.SEGMENT_TICK)
    fun reject(view: View) = feedback(view, HapticFeedbackConstants.REJECT)
    fun error(view: View) = reject(view)
    fun open(view: View) = release(view)
    fun close(view: View) = lift(view)
    fun bump(view: View) = release(view)

    /** Touch feedback and glass deformation share this listener; neither replaces the other. */
    fun attachTo(view: View) {
        if (view is SeekBar || view is SeekBarView || view is CompoundButton || view.getTag(R.id.control_owns_touch) == true) return
        view.setOnTouchListener { touched, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                GlassInstaller.material(touched)?.setTouchPoint(event.x, event.y)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touch(touched)
                MotionEvent.ACTION_UP -> if (event.x in 0f..touched.width.toFloat() && event.y in 0f..touched.height.toFloat()) release(touched)
            }
            false
        }
    }
}
