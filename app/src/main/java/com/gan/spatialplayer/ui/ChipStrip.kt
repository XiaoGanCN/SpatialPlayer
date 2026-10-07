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
 * A strip of small status pills that wraps onto as many lines as it needs.
 *
 * Short facts - the panel's HDR support, whether the spatializer is engaged, which decoder is in
 * use - read better as scannable chips than as a sentence. Each chip carries an optional accent so
 * an "on"/"active" state is distinguishable from a merely informational one without adding an
 * extra line of explanation.
 *
 * ## Why this wraps rather than scrolls
 *
 * It used to sit in a `HorizontalScrollView` with a fading edge, on the theory that the fade reads
 * as "there is more, scroll". What that actually produced was a chip rendered *through* a gradient:
 * the decoder chip, which is one of the more useful things on the row, was the one usually half
 * behind the fade, and it looked broken rather than scrollable. Wrapping costs a line of height on
 * a long set and shows every chip at full contrast, which is the trade worth making for facts that
 * are meant to be read at a glance.
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

    /** Per-child positions resolved during measurement; see [onMeasure]. */
    private val childLeft = ArrayList<Int>()
    private val childTop = ArrayList<Int>()

    /**
     * Whether the positions above are valid.
     *
     * A parent that measures with an unbounded width falls back to the horizontal `LinearLayout`
     * behaviour, and then there are no positions to apply. Without this flag [onLayout] would put
     * every chip at the same place, because a missing entry reads as zero.
     */
    private var wrapped = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (widthMode == MeasureSpec.UNSPECIFIED || childCount == 0) {
            wrapped = false
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        wrapped = true

        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(1)

        childLeft.clear()
        childTop.clear()

        // Children are measured unconstrained so a long label keeps its natural width; the wrapping
        // below is what decides where the lines break, which is not something a chip can work out
        // about its siblings.
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                childLeft.add(0)
                childTop.add(0)
                continue
            }
            child.measure(unbounded, unbounded)
            val lp = child.layoutParams as MarginLayoutParams
            val width = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val height = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (x > 0 && x + width > available) {
                widest = maxOf(widest, x)
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            childLeft.add(x + lp.leftMargin)
            childTop.add(y + lp.topMargin)
            x += width
            rowHeight = maxOf(rowHeight, height)
        }
        widest = maxOf(widest, x)

        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!wrapped) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val left = paddingLeft + (childLeft.getOrNull(i) ?: 0)
            val top = paddingTop + (childTop.getOrNull(i) ?: 0)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
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
        for (chip in chips) {
            val view = buildChip(chip)
            // Chips are single-line by definition; without this a double-digit percentage or a
            // narrow parent can wrap the label and break the pill.
            if (view is TextView) view.maxLines = 1
            addView(view)
        }
        // The strip is refreshed on a timer, so its content width changes without the parent
        // asking for a new measurement. Without these the chips keep the width they were first
        // measured at and clip or wrap as the values change.
        requestLayout()
        invalidate()
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
