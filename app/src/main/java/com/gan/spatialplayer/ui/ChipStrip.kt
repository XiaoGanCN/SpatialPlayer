package com.gan.spatialplayer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gan.spatialplayer.R

/**
 * A strip of small status pills.
 *
 * Short facts - the panel's HDR support, whether the spatializer is engaged, which decoder is in
 * use - read better as scannable chips than as a sentence. Each chip carries an optional accent so
 * an "on"/"active" state is distinguishable from a merely informational one without adding an
 * extra line of explanation.
 */
class ChipStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** How a chip should read. */
    enum class Tone {
        /** Neutral fact. */
        NEUTRAL,

        /** Something is active or good. */
        ACTIVE,

        /** Something is unavailable, or worth noticing. */
        WARN,
    }

    data class Chip(
        val label: String,
        val tone: Tone = Tone.NEUTRAL,
    )

    /**
     * Replaces the strip's contents.
     *
     * Values are rendered in monospace (they are technical) while the surrounding chrome stays in
     * the system face, matching the app's typography rule.
     */
    fun setChips(chips: List<Chip>) {
        removeAllViews()
        visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        for (chip in chips) addView(buildChip(chip))
    }

    private fun buildChip(chip: Chip): View {
        val colors = colorsFor(chip.tone)

        return TextView(context).apply {
            text = chip.label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.06f
            setTextColor(colors.text)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = ChipDrawable(colors.fill, colors.stroke, dp(20).toFloat())
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) }
        }
    }

    private data class ChipColors(val stroke: Int, val text: Int, val fill: Int)

    private fun colorsFor(tone: Tone): ChipColors = when (tone) {
        Tone.NEUTRAL -> ChipColors(
            stroke = ContextCompat.getColor(context, R.color.glass_stroke_soft),
            text = ContextCompat.getColor(context, R.color.text_secondary),
            fill = Color.argb(18, 255, 255, 255),
        )

        Tone.ACTIVE -> ChipColors(
            stroke = ContextCompat.getColor(context, R.color.accent),
            text = ContextCompat.getColor(context, R.color.accent),
            fill = Color.argb(38, 138, 180, 248),
        )

        Tone.WARN -> ChipColors(
            stroke = ContextCompat.getColor(context, R.color.warn),
            text = ContextCompat.getColor(context, R.color.warn),
            fill = Color.argb(34, 242, 178, 92),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
