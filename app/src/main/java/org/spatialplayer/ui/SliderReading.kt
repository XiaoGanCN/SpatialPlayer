package org.spatialplayer.ui

import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.spatialplayer.R
import kotlin.math.roundToInt

/**
 * Makes a slider's numeric reading an interactive value: one confirmed tap opens an editor so the
 * number can be typed, and a double tap restores the default.
 *
 * Why this exists: a slider is the right control for gradual adjustment and the wrong one for
 * "set this to exactly 24" or "back to what it was". Both of those need a keyboard or a single
 * gesture, and the reading is already on screen showing the number, so it is the natural place
 * to put them. A drag stays a drag - the listener is on the reading, not on the bar.
 *
 * The applied value is snapped onto the slider's step grid, so a typed number can never leave
 * the bar showing a position it cannot actually take.
 */
object SliderReading {

    /**
     * @param current supplies the value shown right now, so a reset can be offered even after a
     * drag moved the reading without the caller telling this helper.
     * @param onSet applied an accepted value - typed, or the default. Called exactly once per
     * gesture: the caller moves the bar, updates the reading and persists the setting in it.
     */
    fun attach(
        view: View,
        current: () -> Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        format: (Float) -> String,
        default: Float? = null,
        onSet: (Float) -> Unit,
    ) {
        // The reading is not naturally clickable; say so, or it cannot take focus at all.
        view.setTag(R.id.control_owns_touch, true)
        view.isClickable = true
        view.isFocusable = true

        val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                edit(view, current(), range, step, format, onSet)
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                val target = default
                if (target == null) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                    return false
                }
                apply(view, target, range, step, onSet)
                return true
            }
        })
        view.setOnClickListener { edit(view, current(), range, step, format, onSet) }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) Haptics.touch(view)
            if (event.actionMasked == MotionEvent.ACTION_UP) Haptics.release(view)
            detector.onTouchEvent(event)
            true
        }
    }

    /** One place that maps any incoming value onto the slider's own grid. */
    private fun snap(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
        val steps = ((range.endInclusive - range.start) / step).roundToInt().coerceAtLeast(1)
        val index = ((value - range.start) / step).roundToInt().coerceIn(0, steps)
        return range.start + index * step
    }

    private fun apply(
        view: View,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        onSet: (Float) -> Unit,
    ) {
        onSet(snap(value, range, step))
        Haptics.release(view)
    }

    private fun edit(
        view: View,
        current: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        format: (Float) -> String,
        onSet: (Float) -> Unit,
    ) {
        val context = view.context
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            // Keep units outside the numeric input so the initial value can be submitted unchanged.
            text = android.text.Editable.Factory.getInstance().newEditable(current.toString())
            setSelection(text.length)
            // Numbers without chrome: the range is printed below the field instead.
            background = null
            setPadding(0, context.resources.displayMetrics.density.toInt() * 12, 0, 0)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.slider_edit_value)
            .setView(input)
            .setMessage(
                context.getString(
                    R.string.slider_range_hint,
                    format(range.start),
                    format(range.endInclusive),
                ),
            )
            .setPositiveButton(R.string.apply) { _, _ ->
                val typed = input.text.toString().replace(',', '.').toFloatOrNull()
                if (typed == null) {
                    Toast.makeText(context, R.string.slider_not_a_number, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                apply(view, typed, range, step, onSet)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        input.requestFocus()
        input.selectAll()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        GlassTheme.configureSheet(dialog, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
