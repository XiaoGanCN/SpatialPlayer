package com.gan.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gan.spatialplayer.R

/**
 * The player capsule's overflow.
 *
 * The capsule is a single row, which is what makes it read as one control rather than a stack of
 * panels - but that leaves no room for speed, scaling, subtitles and audio. They live here instead.
 * Each row shows its current value, so the sheet doubles as a readout of state that is no longer
 * visible in the bar: the old row of buttons said "1.00x" and "Scaling" at a glance, and losing that
 * would have been a real regression rather than a simplification.
 *
 * Presented as a bottom sheet for the same reason [InspectorSheet] is: the dialog's *window* can ask
 * the compositor to blur what is behind it, including the video, which nothing drawn inside the
 * activity's own window can do. That is where the glass in this app genuinely comes from.
 */
class OverflowSheet(private val context: Context) {

    /**
     * One row.
     *
     * [detail] is the current value, in monospace, and [onDismiss] runs after the sheet is closed so
     * a row can open another sheet without two dialogs fighting over the window.
     */
    data class Action(
        val label: String,
        val detail: String?,
        val iconRes: Int,
        val run: () -> Unit,
    )

    private var dialog: Dialog? = null
    private var sheet: LinearLayout? = null
    private var onDismissed: (() -> Unit)? = null

    val isOpen: Boolean get() = dialog?.isShowing == true

    /**
     * [dismissed] runs once the sheet is gone. The player uses it to bring its chrome back: the
     * sheet covers the capsule, and translucent rows over a capsule that is still drawn underneath
     * read as a rendering fault rather than as a menu.
     */
    fun show(actions: List<Action>, dismissed: () -> Unit = {}) {
        if (isOpen) return
        onDismissed = dismissed
        val built = build(actions)
        dialog = built
        runCatching { built.show() }.onFailure { error ->
            // Never swallow this: a sheet that refuses to appear is indistinguishable from a tap
            // that was never delivered, and that ambiguity costs far more than the log line.
            Log.w(TAG, "overflow sheet failed to show", error)
            dialog = null
            return
        }
        animateIn()
    }

    fun hide() {
        val current = dialog ?: return
        dialog = null
        runCatching { current.dismiss() }
        onDismissed?.invoke()
        onDismissed = null
    }

    // ------------------------------------------------------------------ construction

    private fun build(actions: List<Action>): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(context)
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // No dim. The window is translucent and relies on the blur behind it; an extra scrim would
        // hide the picture the glass is meant to reveal.
        root.addView(
            View(context).apply {
                setBackgroundColor(Color.argb(40, 0, 0, 0))
                setOnClickListener { hide() }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_sheet_window)
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        sheet = panel

        panel.addView(
            View(context).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            },
        )

        for (action in actions) {
            panel.addView(actionRow(action))
        }

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )

        // The window draws behind the system bars, so the sheet has to keep itself clear of the
        // gesture bar or its last row sits under it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val params = panel.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != dp(10) + bottom) {
                params.bottomMargin = dp(10) + bottom
                panel.layoutParams = params
            }
            insets
        }

        dialog.setContentView(root)
        applyWindow(dialog, root)
        return dialog
    }

    private fun actionRow(action: Action): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_button)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                hide()
                action.run()
            }
        }
        Haptics.attachTo(row)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }

        row.addView(
            ImageView(context).apply {
                setImageResource(action.iconRes)
                setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
                contentDescription = null
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) },
        )

        row.addView(
            TextView(context).apply {
                text = action.label
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        action.detail?.let { detail ->
            row.addView(
                TextView(context).apply {
                    text = detail
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                },
            )
        }
        return row
    }

    private fun applyWindow(dialog: Dialog, content: View) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.setDimAmount(0f)

        // The player runs immersive, but a dialog's window does not inherit that - so without this
        // the status bar and the navigation bar reappear the moment a sheet opens, on top of the
        // picture. Hide them here too, and let a swipe bring them back transiently.
        val controller = WindowCompat.getInsetsController(window, content)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.setGravity(Gravity.BOTTOM)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            dimAmount = 0f
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ask the compositor to blur the picture behind this window. Without it the sheet is a
        // translucent rectangle, which is the difference between frosted and merely tinted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setBackgroundBlurRadius(dp(BLUR_RADIUS_DP)) }
        }
    }

    private fun animateIn() {
        val view = sheet ?: return
        view.post {
            view.translationY = view.height.coerceAtLeast(dp(240)).toFloat()
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .start()
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "OverflowSheet"

        /**
         * Blur radius in dp.
         *
         * The sheet sits over live video, so the frost has to be strong enough to separate the rows
         * from a moving picture without turning the backdrop to mud.
         */
        const val BLUR_RADIUS_DP = 22
    }
}
