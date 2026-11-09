package com.gan.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Compact command palette, using the same material as Settings and the inspector. */
class OverflowSheet(private val context: Context) {
    data class Action(val label: String, val detail: String?, val iconRes: Int, val run: () -> Unit)
    private var dialog: Dialog? = null
    val isOpen: Boolean get() = dialog?.isShowing == true
    fun hide() { dialog?.dismiss() }
    fun show(actions: List<Action>, dismissed: () -> Unit = {}) {
        if (isOpen) return
        val d = Dialog(context, com.gan.spatialplayer.R.style.Theme_SpatialPlayer_Glass)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        for (action in actions) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GlassTheme.control(context)
                minimumHeight = dp(58)
            }
            row.addView(ImageView(context).apply { setImageResource(action.iconRes) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(TextView(context).apply {
                text = action.label
                textSize = 16f
                setTextColor(0xFFF5F5F7.toInt())
                setPadding(dp(14), 0, 0, 0)
                typeface = UiTypography.body(context)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(context).apply {
                text = action.detail.orEmpty()
                textSize = 13f
                maxLines = 1
                maxWidth = dp(140)
                setTextColor(0xAFFFFFFF.toInt())
            })
            row.setOnClickListener { d.dismiss(); action.run() }
            Haptics.attachTo(row)
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        d.setContentView(ScrollView(context).apply { addView(column) })
        d.setOnDismissListener { dialog = null; dismissed() }
        dialog = d
        d.show()
        GlassTheme.configureSheet(d, minOf(dp(actions.size * 64 + 24), (context.resources.displayMetrics.heightPixels * .78f).toInt()))
        column.alpha = 0f
        column.translationY = dp(20).toFloat()
        column.animate().alpha(1f).translationY(0f).setDuration(240).start()
    }
    private fun dp(value: Int) = GlassTheme.dp(context, value.toFloat())
}
