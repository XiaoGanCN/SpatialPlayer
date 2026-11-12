package org.spatialplayer.ui

import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
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
    internal var activeEditor: View? = null
        private set

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
                apply(target, range, step, onSet)
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
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        onSet: (Float) -> Unit,
    ) {
        onSet(snap(value, range, step))
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

        val dialog = android.app.Dialog(context, R.style.Theme_SpatialPlayer_Glass)
        val panel = GlassSheetLayout(context, context.getString(R.string.slider_edit_value), { dialog.dismiss() })
        val density = context.resources.displayMetrics.density
        val gap = (12 * density).toInt()
        input.setTextColor(android.graphics.Color.WHITE)
        input.textSize = 28f
        input.gravity = android.view.Gravity.CENTER
        input.minimumHeight = (64*density).toInt()
        input.setTag(R.id.glass_role, R.id.glass_role_panel)
        input.background = GlassTheme.surface(context)
        input.setPadding(gap,gap,gap,gap)
        val rangeHint = context.getString(R.string.slider_range_hint, format(range.start), format(range.endInclusive))
        panel.content.addView(android.widget.TextView(context).apply {
            text = rangeHint; textSize = 13f; setTextColor(0xC7FFFFFF.toInt())
            setPadding(0,0,0,gap)
        })
        panel.content.addView(input, android.widget.LinearLayout.LayoutParams(-1,-2))
        fun submit() {
            val typed = input.text.toString().replace(',', '.').toFloatOrNull()
            if (typed == null || !typed.isFinite()) {
                input.error = context.getString(R.string.slider_not_a_number)
                return
            }
            apply(typed, range, step, onSet)
            dialog.dismiss()
        }
        val actions = android.widget.LinearLayout(context).apply { gravity = android.view.Gravity.END }
        for ((label, action) in listOf(context.getString(android.R.string.cancel) to { dialog.dismiss() },
            context.getString(R.string.apply) to { submit() })) {
            actions.addView(android.widget.TextView(context).apply {
                text = label; textSize = 16f; gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = GlassTheme.control(context)
                setTag(R.id.glass_role, if (label == context.getString(R.string.apply)) R.id.glass_role_button_active else R.id.glass_role_button)
                setOnClickListener { action() }
            }, android.widget.LinearLayout.LayoutParams(0,(48*density).toInt(),1f).apply { setMargins(gap/2,gap,gap/2,0) })
        }
        panel.content.addView(actions)
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        activeEditor = panel
        dialog.setOnDismissListener { activeEditor = null }
        dialog.setContentView(panel)
        dialog.show()
        GlassTheme.configureSheet(dialog, minOf((310*density).toInt(), (context.resources.displayMetrics.heightPixels*.60f).toInt()))
        GlassInstaller.apply(panel)
        input.requestFocus()
        input.selectAll()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input.post { context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }
}
