package org.spatialplayer.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.spatialplayer.R

/** The same glass shell, safe scrolling and navigation for the player menu and every subpanel. */
internal class GlassSheetLayout(context: Context, label: String, close: () -> Unit, back: (() -> Unit)? = null) : LinearLayout(context) {
    val title = TextView(context).apply {
        text = label; textSize = 18f; typeface = UiTypography.body(context)
        setTextColor(Color.WHITE)
    }
    val content = LinearLayout(context).apply {
        orientation = VERTICAL
        id = R.id.sheet_content
        setPadding(dp(16), dp(4), dp(16), dp(20))
    }
    val scroll = ScrollView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(-1, -2))
    }
    init {
        orientation = VERTICAL
        id = R.id.sheet_panel
        setBackgroundResource(R.drawable.bg_glass_panel)
        setTag(R.id.glass_role, R.id.glass_role_sheet)
        addView(View(context).apply { background = GlassTheme.surface(context, 3f, true) },
            LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(6) })
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        fun button(icon: Int, tagName: String, description: String, action: () -> Unit) = ImageButton(context).apply {
            tag = tagName
            setTag(R.id.glass_role, R.id.glass_role_button)
            background = GlassTheme.control(context)
            setImageResource(icon); setColorFilter(Color.WHITE)
            contentDescription = description
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { action() }
        }
        if (back != null) header.addView(button(R.drawable.ic_chevron_left, "sheet_back", "Back to playback controls", back),
            LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        header.addView(title, LayoutParams(0, -2, 1f))
        header.addView(button(R.drawable.ic_close, "sheet_close", "Close", close), LayoutParams(dp(44), dp(44)).apply { marginStart = dp(12) })
        addView(header, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
    }
    private fun dp(value: Int) = GlassTheme.dp(context, value.toFloat())
}
